package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.BuyerRefundDto;
import com.techcomfort.landvaultbackend.payments.dto.RefundDto;
import com.techcomfort.landvaultbackend.payments.dto.RefundDueDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackBankDirectory;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Refunds (decided with the user): a Super Admin returns the FULL amount a
 * buyer paid for a plot they never got, once. Where Paystack can't return a
 * bank-transfer payment on its own, the buyer gives an account in their own
 * name and the Super Admin sends it there. See AGENTS.md, "Payments", refunds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRecorder recorder;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaystackClient paystack;
    private final PaystackBankDirectory bankDirectory;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;
    private final SettlementAlertSender alerts;

    // --- LandVault's side ---

    /** Payments owed back with no refund under way (a failed one doesn't count — it may be retried). */
    @Transactional(readOnly = true)
    public List<RefundDueDto> due() {
        List<Payment> owed = payments.findAllByStatusAndRefundRequestedAtIsNotNullOrderByRefundRequestedAtAsc(
                PaymentStatus.SUCCEEDED);
        if (owed.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Refund>> byPayment = refunds.findAllByPaymentIdInOrderByCreatedAtDesc(
                owed.stream().map(Payment::getId).toList()).stream().collect(Collectors.groupingBy(Refund::getPaymentId));
        return owed.stream()
                .filter(p -> byPayment.getOrDefault(p.getId(), List.of()).stream()
                        .allMatch(r -> r.getStatus() == RefundStatus.FAILED))
                .map(p -> new RefundDueDto(p.getId(), p.getReference(), p.getTransactionId(),
                        identityApi.fullNameOf(p.getBuyerUserId()).orElse(null),
                        identityApi.emailOf(p.getBuyerUserId()).orElse(null), p.getAmount(), p.getCurrency().name(),
                        p.getChannel(), p.getLast4(), p.getPaidAt(), p.getReviewReason(), p.getRefundRequestedAt(),
                        byPayment.getOrDefault(p.getId(), List.of()).stream().findFirst()
                                .map(r -> toDto(r, p)).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RefundDto> list(RefundStatus status) {
        List<Refund> rows = status == null ? refunds.findAllByOrderByCreatedAtDesc()
                : refunds.findAllByStatusOrderByCreatedAtDesc(status);
        Map<UUID, Payment> paymentsById = payments.findAllById(rows.stream().map(Refund::getPaymentId).toList())
                .stream().collect(Collectors.toMap(Payment::getId, Function.identity()));
        return rows.stream().map(r -> toDto(r, paymentsById.get(r.getPaymentId()))).toList();
    }

    @Transactional(readOnly = true)
    public RefundDto get(UUID refundId) {
        Refund refund = refunds.findById(refundId).orElseThrow(PaymentException.RefundNotFound::new);
        return toDto(refund, payments.findById(refund.getPaymentId()).orElse(null));
    }

    /**
     * Saves the refund, then asks Paystack — deliberately not one transaction,
     * like payouts. A resend after a lost reply simply asks again: Paystack
     * never refunds more than was paid, so a duplicate is refused, not paid.
     */
    public RefundDto start(String paymentReference) {
        UUID actor = requireTwoFactor();
        RefundRecorder.Prepared prepared = recorder.prepare(paymentReference, actor);
        PaystackClient.Refund answer;
        try {
            answer = paystack.createRefund(prepared.paymentReference(), prepared.amountKobo(),
                    "Refund for your LandVault payment " + prepared.paymentReference(),
                    "LandVault refund " + prepared.refundId());
        } catch (PaymentException.GatewayUnavailable e) {
            log.warn("Refund {} sent to Paystack with no answer; kept as sending", prepared.refundId());
            throw new PaymentException.RefundOutcomeUnknown("Paystack didn't answer.");
        } catch (PaymentException.GatewayRefused e) {
            if (prepared.resend()) {
                recorder.resendRefused(prepared.refundId(), e.paystackMessage());
                throw new PaymentException.RefundOutcomeUnknown("Paystack said: " + e.paystackMessage()
                        + " — it may already have this refund; check the Paystack dashboard.");
            }
            recorder.refused(prepared.refundId(), e.paystackMessage(), actor);
            throw e;
        }
        Refund refund = recorder.record(prepared.refundId(), answer, actor);
        if (refund.getStatus() == RefundStatus.NEEDS_ATTENTION) {
            askBuyerForAccount(refund, prepared.paymentReference());
        }
        log.info("Refund {} started by {}: {}", refund.getId(), actor, refund.getStatus().wire());
        return get(refund.getId());
    }

    /** Sends a needs-attention refund to the account the buyer gave. Needs the sender's 2FA. */
    @Transactional
    public RefundDto sendToAccount(UUID refundId) {
        UUID actor = requireTwoFactor();
        Refund refund = refunds.findByIdForUpdate(refundId).orElseThrow(PaymentException.RefundNotFound::new);
        if (refund.getStatus() != RefundStatus.NEEDS_ATTENTION) {
            throw new PaymentException.RefundNotAwaitingAccount(refund.getStatus().wire());
        }
        if (refund.getAccountNumber() == null) {
            throw new PaymentException.RefundAccountMissing();
        }
        PaystackClient.Bank bank = bankDirectory.find(refund.getAccountBankCode())
                .orElseThrow(PaymentException.UnknownBank::new);
        PaystackClient.Refund answer = paystack.retryRefundWithAccount(refund.getPaystackRefundId(),
                refund.getAccountNumber(), bank.id());
        refund.setStatus(RefundStatus.fromPaystack(answer.status()));
        refund.setGatewayMessage(RefundRecorder.truncate(answer.message()));
        refund.setExpectedAt(answer.expectedAt());
        refund.setAccountSentBy(actor);
        refund.setAccountSentAt(Instant.now());
        refunds.save(refund);
        auditApi.record(AuditEntryRequest.of(actor, "refund.sent_to_account", "refund", refund.getId(),
                refund.getSellerTenantId(), "Refund sent to " + refund.getAccountBankName() + " ending "
                        + PayoutRecorder.last4(refund.getAccountNumber()) + " (\"" + refund.getAccountName()
                        + "\"); now " + refund.getStatus().wire() + "."));
        return toDto(refund, payments.findById(refund.getPaymentId()).orElse(null));
    }

    // --- the buyer's side ---

    /** The buyer's own refund for a payment; someone else's payment is indistinguishable from none. */
    @Transactional(readOnly = true)
    public BuyerRefundDto forBuyer(String paymentReference) {
        Payment payment = ownPayment(paymentReference);
        Refund refund = latest(payment).orElseThrow(PaymentException.RefundNotFound::new);
        return toBuyerDto(refund);
    }

    /**
     * The buyer gives an account for a needs-attention refund. The name is
     * what the bank returns, never typed; the reviewer sees it beside the
     * buyer's own name. May be replaced until it has been sent.
     */
    @Transactional
    public BuyerRefundDto submitAccount(String paymentReference, String bankCode, String accountNumber) {
        Payment payment = ownPayment(paymentReference);
        Refund refund = refunds.findLiveForUpdate(payment.getId(), RefundStatus.FAILED)
                .orElseThrow(PaymentException.RefundNotFound::new);
        if (refund.getStatus() != RefundStatus.NEEDS_ATTENTION) {
            throw new PaymentException.RefundNotAwaitingAccount(refund.getStatus().wire());
        }
        PaystackClient.Bank bank = bankDirectory.find(bankCode.trim()).orElseThrow(PaymentException.UnknownBank::new);
        String bankName = paystack.resolveAccountName(accountNumber, bank.code())
                .orElseThrow(PaymentException.AccountNotResolved::new);
        refund.setAccountBankCode(bank.code());
        refund.setAccountBankName(bank.name());
        refund.setAccountNumber(accountNumber);
        refund.setAccountName(bankName);
        refund.setAccountSubmittedAt(Instant.now());
        refunds.save(refund);
        auditApi.record(AuditEntryRequest.of(payment.getBuyerUserId(), "refund.account_submitted", "refund",
                refund.getId(), refund.getSellerTenantId(), "Buyer gave " + bank.name() + " ending "
                        + PayoutRecorder.last4(accountNumber) + " (bank name \"" + bankName + "\") for the refund."));
        return toBuyerDto(refund);
    }

    // --- helpers ---

    private void askBuyerForAccount(Refund refund, String paymentReference) {
        identityApi.emailOf(refund.getBuyerUserId()).ifPresent(to -> alerts.refundNeedsAccount(
                new SettlementAlertSender.RefundNeedsAccountAlert(to, refund.getAmount().toPlainString(),
                        paymentReference)));
    }

    private Payment ownPayment(String paymentReference) {
        UUID buyer = currentScope().userId();
        return payments.findByReference(paymentReference)
                .filter(p -> p.getBuyerUserId().equals(buyer))
                .orElseThrow(PaymentException.PaymentNotFound::new);
    }

    private Optional<Refund> latest(Payment payment) {
        return refunds.findAllByPaymentIdInOrderByCreatedAtDesc(List.of(payment.getId())).stream()
                .max(Comparator.comparing(Refund::getCreatedAt));
    }

    private RefundDto toDto(Refund r, Payment payment) {
        String buyerName = identityApi.fullNameOf(r.getBuyerUserId()).orElse(null);
        RefundDto.RefundAccount account = r.getAccountNumber() == null ? null : new RefundDto.RefundAccount(
                r.getAccountBankName(), r.getAccountNumber(), r.getAccountName(), r.getAccountSubmittedAt(),
                r.getAccountSentAt());
        return new RefundDto(r.getId(), r.getPaymentId(), payment == null ? null : payment.getReference(),
                r.getTransactionId(), buyerName, identityApi.emailOf(r.getBuyerUserId()).orElse(null), r.getAmount(),
                r.getCurrency().name(), r.getStatus().wire(), r.getGatewayMessage(), r.getReason(), r.getCreatedAt(),
                r.getExpectedAt(), r.getRefundedAt(), account,
                r.getAccountName() == null ? null : SettlementAccountNames.matchesPerson(r.getAccountName(), buyerName));
    }

    private static BuyerRefundDto toBuyerDto(Refund r) {
        return new BuyerRefundDto(r.getStatus().wire(), r.getAmount(), r.getCurrency().name(),
                r.getStatus() == RefundStatus.NEEDS_ATTENTION && r.getAccountSentAt() == null,
                r.getAccountBankName(), r.getAccountNumber() == null ? null : PayoutRecorder.last4(r.getAccountNumber()),
                r.getExpectedAt(), r.getRefundedAt());
    }

    private UUID requireTwoFactor() {
        UUID actor = currentScope().userId();
        if (!identityApi.hasConfirmedTwoFactor(actor)) {
            throw new PaymentException.TwoFactorRequired();
        }
        return actor;
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
