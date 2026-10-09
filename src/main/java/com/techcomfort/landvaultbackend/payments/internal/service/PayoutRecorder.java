package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.common.Currency;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The database half of sending a payout, each step its own short transaction
 * (called from {@link PayoutService}, never from inside one). That is the
 * point: the row is COMMITTED as {@code sending} before Paystack is asked, so
 * a reply lost in transit can never lose track of a request we made.
 */
@Service
@RequiredArgsConstructor
public class PayoutRecorder {

    private static final String REFERENCE_PREFIX = "lv-payout-";

    private final PayoutRepository payouts;
    private final SettlementAccountRepository accounts;
    private final CheckoutApi checkoutApi;
    private final TenancyApi tenancyApi;
    private final AuditApi auditApi;

    /** Paystack's cap on one transfer; a bigger sale is paid in equal parts. */
    @Value("${landvault.payouts.max-transfer-amount}")
    private BigDecimal maxTransferAmount;

    /**
     * How a sale is being paid: {@code partCount} equal parts, of which
     * {@code paidParts} have succeeded. {@code nextPart} is null when all are paid.
     */
    public record Plan(long totalKobo, int partCount, Set<Integer> paidParts, Integer nextPart) {

        public long nextPartKobo() {
            return PayoutSplit.partKobo(totalKobo, partCount, nextPart);
        }

        public long paidKobo() {
            return paidParts.stream().mapToLong(p -> PayoutSplit.partKobo(totalKobo, partCount, p)).sum();
        }
    }

    /**
     * The split is fixed by the first part actually paid and copied by every
     * later part — so a cap raised halfway can't make the parts stop adding up
     * to the sale. While nothing has been paid, it follows the current cap.
     */
    public Plan plan(VerifiedSale sale, List<Payout> attempts) {
        long totalKobo = PaystackAmounts.toKobo(sale.totalPrice(), sale.currency());
        List<Payout> paid = attempts.stream().filter(p -> p.getStatus() == PayoutStatus.SUCCESS).toList();
        int partCount = paid.isEmpty()
                ? PayoutSplit.partCount(totalKobo, PaystackAmounts.toKobo(maxTransferAmount, Currency.NGN))
                : paid.getFirst().getPartCount();
        Set<Integer> paidParts = paid.stream().map(Payout::getPartNumber).collect(Collectors.toSet());
        Integer next = IntStream.rangeClosed(1, partCount).filter(p -> !paidParts.contains(p)).boxed()
                .findFirst().orElse(null);
        return new Plan(totalKobo, partCount, paidParts, next);
    }

    /** What {@link PayoutService} needs to ask Paystack. {@code resend} = an earlier attempt's reply was lost. */
    public record Prepared(UUID payoutId, String reference, long amountKobo, String recipientCode, String reason,
                           boolean resend) {
    }

    /**
     * Every check, then a {@code sending} row. A sale already {@code sending}
     * is handed back to resend with its own reference; any other live payout
     * refuses.
     */
    @Transactional
    public Prepared prepare(UUID transactionId, UUID actor) {
        List<Payout> attempts = payouts.findAllLockedByTransactionId(transactionId);
        Optional<Payout> sending = attempts.stream().filter(p -> p.getStatus() == PayoutStatus.SENDING).findFirst();
        if (sending.isPresent()) {
            return prepared(sending.get(), true);
        }
        Optional<Payout> inFlight = attempts.stream()
                .filter(p -> p.getStatus() == PayoutStatus.AWAITING_OTP || p.getStatus() == PayoutStatus.PENDING)
                .findFirst();
        if (inFlight.isPresent()) {
            throw new PaymentException.PayoutInProgress(inFlight.get().getStatus().wire());
        }
        VerifiedSale sale = checkoutApi.verifiedSale(transactionId).orElseThrow(PaymentException.SaleNotPayable::new);
        Plan plan = plan(sale, attempts);
        if (plan.nextPart() == null) {
            throw new PaymentException.SaleAlreadyPaid();
        }
        if (!tenancyApi.isTenantActive(sale.sellerTenantId())) {
            throw new PaymentException.CompanyNotActive();
        }
        SettlementAccount account = accounts.findByTenantIdAndStatus(sale.sellerTenantId(),
                SettlementAccountStatus.APPROVED).orElseThrow(PaymentException.NoApprovedPayoutAccount::new);
        long kobo = plan.nextPartKobo();

        Payout payout = Payout.builder()
                .tenantId(sale.sellerTenantId())
                .transactionId(transactionId)
                .settlementAccountId(account.getId())
                .recipientCode(account.getRecipientCode())
                .amount(PaystackAmounts.fromKobo(kobo))
                .currency(Currency.NGN)
                .amountKobo(kobo)
                .partNumber(plan.nextPart())
                .partCount(plan.partCount())
                .reference(REFERENCE_PREFIX + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT))
                .status(PayoutStatus.SENDING)
                .initiatedBy(actor)
                .build();
        try {
            payout = payouts.saveAndFlush(payout);
        } catch (DataIntegrityViolationException e) {
            // The one-live-transfer-per-part index: another attempt for this part landed at the same moment.
            throw new PaymentException.PayoutInProgress("being sent");
        }
        auditApi.record(AuditEntryRequest.of(actor, "payout.started", "payout", payout.getId(), sale.sellerTenantId(),
                "Payout part " + plan.nextPart() + " of " + plan.partCount() + ": NGN "
                        + payout.getAmount().toPlainString() + " of " + sale.totalPrice().toPlainString() + " for "
                        + sale.transactionReference() + " (" + sale.plotLabel() + ") to " + account.getBankName()
                        + " ending " + last4(account.getAccountNumber()) + ", reference " + payout.getReference() + "."));
        return prepared(payout, false);
    }

    /** Records Paystack's answer. Only an unfinished payout moves; a finished one is left alone. */
    @Transactional
    public Payout record(UUID payoutId, PaystackClient.Transfer transfer, UUID actor) {
        Payout payout = payouts.findByIdForUpdate(payoutId).orElseThrow(PaymentException.PayoutNotFound::new);
        PayoutStatus next = PayoutStatus.fromPaystack(transfer.status());
        boolean unfinished = payout.getStatus() == PayoutStatus.SENDING || payout.getStatus() == PayoutStatus.AWAITING_OTP;
        if (!unfinished || next == payout.getStatus()) {
            if (transfer.transferCode() != null && payout.getTransferCode() == null) {
                payout.setTransferCode(transfer.transferCode());
                payouts.save(payout);
            }
            return payout;
        }
        if (payout.getStatus() == PayoutStatus.AWAITING_OTP && next != PayoutStatus.AWAITING_OTP) {
            payout.setFinalizedBy(actor);
        }
        payout.setStatus(next);
        if (transfer.transferCode() != null) {
            payout.setTransferCode(transfer.transferCode());
        }
        payout.setGatewayMessage(truncate(transfer.message()));
        payout.setTransferredAt(transfer.transferredAt());
        payouts.save(payout);
        auditApi.record(AuditEntryRequest.of(actor, "payout." + next.wire(), "payout", payout.getId(),
                payout.getTenantId(), "Payout " + payout.getReference() + " is now " + next.wire()
                        + (transfer.message() == null ? "." : " — Paystack: " + transfer.message())));
        return payout;
    }

    /** Paystack refused to start it, so no money moved: the attempt fails and the sale is free to try again. */
    @Transactional
    public Payout refused(UUID payoutId, String paystackMessage, UUID actor) {
        Payout payout = payouts.findByIdForUpdate(payoutId).orElseThrow(PaymentException.PayoutNotFound::new);
        if (payout.getStatus() != PayoutStatus.SENDING) {
            return payout;
        }
        payout.setStatus(PayoutStatus.FAILED);
        payout.setGatewayMessage(truncate(paystackMessage));
        payouts.save(payout);
        auditApi.record(AuditEntryRequest.of(actor, "payout.failed", "payout", payout.getId(), payout.getTenantId(),
                "Payout " + payout.getReference() + " refused by Paystack: " + paystackMessage));
        return payout;
    }

    private static Prepared prepared(Payout payout, boolean resend) {
        return new Prepared(payout.getId(), payout.getReference(), payout.getAmountKobo(), payout.getRecipientCode(),
                "LandVault payout " + payout.getReference()
                        + (payout.getPartCount() > 1 ? " (part " + payout.getPartNumber() + " of " + payout.getPartCount() + ")" : ""),
                resend);
    }

    static String last4(String accountNumber) {
        return accountNumber.substring(accountNumber.length() - 4);
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }
}
