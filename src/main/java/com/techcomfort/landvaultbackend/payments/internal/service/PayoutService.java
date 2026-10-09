package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.PayoutDto;
import com.techcomfort.landvaultbackend.payments.dto.ReadyPayoutDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payout;
import com.techcomfort.landvaultbackend.payments.internal.domain.SettlementAccount;
import com.techcomfort.landvaultbackend.payments.internal.enums.PayoutStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.SettlementAccountStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackAmounts;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.PayoutRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.SettlementAccountRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TR-1: a Super Admin pays a company for a verified sale (decided with the
 * user — money leaving can't be undone, so a person presses it). Paystack's
 * transfer OTP stays on: the transfer waits for the code Paystack sends the
 * account owner. See AGENTS.md, "Payments", step 8b.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private static final String NO_APPROVED_ACCOUNT = "NO_APPROVED_PAYOUT_ACCOUNT";
    private static final String COMPANY_NOT_ACTIVE = "COMPANY_NOT_ACTIVE";
    private static final String CURRENCY_NOT_SUPPORTED = "CURRENCY_NOT_SUPPORTED";

    private final PayoutRecorder recorder;
    private final PayoutRepository payouts;
    private final SettlementAccountRepository accounts;
    private final PaystackClient paystack;
    private final CheckoutApi checkoutApi;
    private final TenancyApi tenancyApi;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;

    /**
     * Verified sales not yet paid in full and with nothing in flight, each
     * with what is left, the next part, and what (if anything) stops it now.
     */
    @Transactional(readOnly = true)
    public List<ReadyPayoutDto> ready() {
        List<VerifiedSale> sales = checkoutApi.verifiedSales();
        if (sales.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Payout>> attemptsBySale = payouts.findAllByTransactionIdInOrderByCreatedAtDesc(
                        sales.stream().map(VerifiedSale::transactionId).toList()).stream()
                .collect(Collectors.groupingBy(Payout::getTransactionId));
        Set<UUID> tenants = new HashSet<>(sales.stream().map(VerifiedSale::sellerTenantId).toList());
        Map<UUID, String> names = tenancyApi.organizationNamesFor(tenants);
        Map<UUID, SettlementAccount> byId = accountsById(attemptsBySale.values().stream().flatMap(List::stream).toList());

        List<ReadyPayoutDto> ready = new ArrayList<>();
        for (VerifiedSale sale : sales) {
            List<Payout> attempts = attemptsBySale.getOrDefault(sale.transactionId(), List.of());
            boolean inFlight = attempts.stream().anyMatch(p -> p.getStatus() == PayoutStatus.SENDING
                    || p.getStatus() == PayoutStatus.AWAITING_OTP || p.getStatus() == PayoutStatus.PENDING);
            if (inFlight) {
                continue;
            }
            List<String> blockers = new ArrayList<>();
            PayoutRecorder.Plan plan = null;
            if (sale.currency() == Currency.NGN) {
                plan = recorder.plan(sale, attempts);
                if (plan.nextPart() == null) {
                    continue;
                }
            } else {
                blockers.add(CURRENCY_NOT_SUPPORTED);
            }
            Optional<SettlementAccount> account = accounts.findByTenantIdAndStatus(sale.sellerTenantId(),
                    SettlementAccountStatus.APPROVED);
            if (account.isEmpty()) {
                blockers.add(NO_APPROVED_ACCOUNT);
            }
            if (!tenancyApi.isTenantActive(sale.sellerTenantId())) {
                blockers.add(COMPANY_NOT_ACTIVE);
            }
            PayoutDto lastAttempt = attempts.stream().findFirst()
                    .map(p -> toDto(p, byId.get(p.getSettlementAccountId()))).orElse(null);
            ready.add(new ReadyPayoutDto(sale.transactionId(), sale.transactionReference(), sale.sellerTenantId(),
                    names.get(sale.sellerTenantId()), sale.estateName(), sale.plotLabel(), sale.totalPrice(),
                    sale.currency().name(),
                    plan == null ? null : PaystackAmounts.fromKobo(plan.totalKobo() - plan.paidKobo()),
                    plan == null ? null : plan.partCount(), plan == null ? null : plan.paidParts().size(),
                    plan == null ? null : plan.nextPart(),
                    plan == null ? null : PaystackAmounts.fromKobo(plan.nextPartKobo()), sale.verifiedAt(),
                    account.map(a -> new ReadyPayoutDto.PayoutAccount(a.getBankName(),
                            PayoutRecorder.last4(a.getAccountNumber()), a.getAccountName())).orElse(null),
                    blockers, lastAttempt));
        }
        return ready;
    }

    /**
     * TR-3: the company's own payouts, read-only, for its finance officer and
     * Executive Director. Company-wide only; RLS (changeset 076) limits the
     * rows to the caller's company either way.
     */
    @Transactional(readOnly = true)
    public List<PayoutDto> forCompany() {
        TenantScope scope = currentScope();
        if (scope.tenantId() == null) {
            throw new PaymentException.CompanyScopeRequired();
        }
        if (scope.branchId() != null) {
            throw new PaymentException.CompanyWideScopeRequired();
        }
        List<Payout> rows = payouts.findAllByTenantIdOrderByCreatedAtDesc(scope.tenantId());
        Map<UUID, SettlementAccount> byId = accountsById(rows);
        return rows.stream().map(p -> toDto(p, byId.get(p.getSettlementAccountId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<PayoutDto> list(PayoutStatus status) {
        List<Payout> rows = status == null ? payouts.findAllByOrderByCreatedAtDesc()
                : payouts.findAllByStatusOrderByCreatedAtDesc(status);
        Map<UUID, SettlementAccount> byId = accountsById(rows);
        return rows.stream().map(p -> toDto(p, byId.get(p.getSettlementAccountId()))).toList();
    }

    /**
     * Saves the attempt, then asks Paystack. Deliberately NOT one transaction:
     * the {@code sending} row is committed first, so a lost reply leaves a
     * record to resend from. A resend first asks Paystack whether it already
     * has that reference (decided with the user — never a second transfer).
     */
    public PayoutDto send(UUID transactionId) {
        UUID actor = requireTwoFactor();
        PayoutRecorder.Prepared prepared = recorder.prepare(transactionId, actor);
        if (prepared.resend()) {
            PaystackClient.Transfer known = paystack.verifyTransfer(prepared.reference());
            if (known.found()) {
                return dto(recorder.record(prepared.payoutId(), known, actor));
            }
        }
        PaystackClient.Transfer transfer;
        try {
            transfer = paystack.initiateTransfer(prepared.amountKobo(), prepared.recipientCode(),
                    prepared.reference(), prepared.reason());
        } catch (PaymentException.GatewayUnavailable e) {
            log.warn("Payout {} sent to Paystack with no answer; kept as sending", prepared.reference());
            throw new PaymentException.PayoutOutcomeUnknown();
        } catch (PaymentException.GatewayRefused e) {
            recorder.refused(prepared.payoutId(), e.paystackMessage(), actor);
            log.info("Payout {} refused by Paystack", prepared.reference());
            throw e;
        }
        Payout payout = recorder.record(prepared.payoutId(), transfer, actor);
        log.info("Payout {} started by {}: {}", payout.getReference(), actor, payout.getStatus().wire());
        return dto(payout);
    }

    /**
     * Passes the code straight to Paystack — never stored or logged. If
     * Paystack refuses or doesn't answer, ask it what the transfer's state is:
     * a code that did go through before the reply was lost must not look
     * like a failure.
     */
    @Transactional
    public PayoutDto submitOtp(UUID payoutId, String otp) {
        UUID actor = requireTwoFactor();
        Payout payout = awaitingOtp(payoutId);
        PaystackClient.Transfer transfer;
        try {
            transfer = paystack.finalizeTransfer(payout.getTransferCode(), otp);
        } catch (PaymentException.GatewayRefused | PaymentException.GatewayUnavailable e) {
            PaystackClient.Transfer known = paystack.verifyTransfer(payout.getReference());
            if (known.found() && PayoutStatus.fromPaystack(known.status()) != PayoutStatus.AWAITING_OTP) {
                return dto(recorder.record(payoutId, known, actor));
            }
            if (e instanceof PaymentException.GatewayRefused refused) {
                throw new PaymentException.OtpRejected(refused.paystackMessage());
            }
            throw e;
        }
        return dto(recorder.record(payoutId, transfer, actor));
    }

    @Transactional
    public PayoutDto resendOtp(UUID payoutId) {
        UUID actor = requireTwoFactor();
        Payout payout = awaitingOtp(payoutId);
        paystack.resendTransferOtp(payout.getTransferCode());
        auditApi.record(AuditEntryRequest.of(actor, "payout.otp_resent", "payout", payout.getId(),
                payout.getTenantId(), "Paystack asked to resend the code for payout " + payout.getReference() + "."));
        return dto(payout);
    }

    /**
     * Stops a payout still waiting for its code. Nothing was sent: Paystack
     * only moves money once we finalize, and a cancelled payout never is.
     */
    @Transactional
    public PayoutDto cancel(UUID payoutId) {
        UUID actor = currentScope().userId();
        Payout payout = awaitingOtp(payoutId);
        payout.setStatus(PayoutStatus.CANCELLED);
        payout.setCancelledBy(actor);
        payouts.save(payout);
        auditApi.record(AuditEntryRequest.of(actor, "payout.cancelled", "payout", payout.getId(),
                payout.getTenantId(), "Payout " + payout.getReference() + " cancelled before its code was entered."));
        return dto(payout);
    }

    // --- helpers ---

    private Payout awaitingOtp(UUID payoutId) {
        Payout payout = payouts.findByIdForUpdate(payoutId).orElseThrow(PaymentException.PayoutNotFound::new);
        if (payout.getStatus() != PayoutStatus.AWAITING_OTP) {
            throw new PaymentException.PayoutNotAwaitingOtp(payout.getStatus().wire());
        }
        return payout;
    }

    private UUID requireTwoFactor() {
        UUID actor = currentScope().userId();
        if (!identityApi.hasConfirmedTwoFactor(actor)) {
            throw new PaymentException.TwoFactorRequired();
        }
        return actor;
    }

    private PayoutDto dto(Payout payout) {
        return toDto(payout, accounts.findById(payout.getSettlementAccountId()).orElse(null));
    }

    private Map<UUID, SettlementAccount> accountsById(List<Payout> rows) {
        Set<UUID> ids = rows.stream().map(Payout::getSettlementAccountId).collect(Collectors.toSet());
        return accounts.findAllById(ids).stream().collect(Collectors.toMap(SettlementAccount::getId, Function.identity()));
    }

    private static PayoutDto toDto(Payout p, SettlementAccount account) {
        return new PayoutDto(p.getId(), p.getTransactionId(), p.getTenantId(), p.getAmount(), p.getCurrency().name(),
                p.getPartNumber(), p.getPartCount(), p.getStatus().wire(), p.getReference(), p.getGatewayMessage(),
                account == null ? null : account.getBankName(),
                account == null ? null : PayoutRecorder.last4(account.getAccountNumber()),
                p.getCreatedAt(), p.getTransferredAt(), p.getReviewReason());
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
