package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.PaymentDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackAmounts;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackProperties;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PY-1: start paying for a transaction. The amount is the price agreed at
 * reservation — never the request — converted to kobo in exactly one place.
 * Nothing here decides a payment succeeded; that is Paystack's verify call,
 * in the confirmation step. See AGENTS.md, "Payments".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final CheckoutApi checkoutApi;
    private final IdentityApi identityApi;
    private final PaystackClient paystack;
    private final PaystackProperties paystackProperties;
    private final PaymentRepository payments;
    private final AuditApi auditApi;

    /** How long an unpaid link is handed back again instead of opening a second payment. */
    @Value("${landvault.payments.link-reuse-window:30m}")
    private Duration linkReuseWindow;

    /** A payment attempt, and whether it was just created or an open one handed back. */
    public record Started(PaymentDto payment, boolean created) {
    }

    /** What this payment is for, and how much. */
    private record Due(BigDecimal amount, PaymentPurpose purpose, Integer instalmentSequence) {
    }

    /**
     * Starts the next payment: the full price, a plan's deposit, or — once the
     * deposit is verified (3b) — the next instalment, or with {@code balance}
     * everything still owed. Always from the fixed schedule, never the request.
     */
    @Transactional
    public Started start(UUID transactionId, boolean balance) {
        UUID buyerId = currentScope().userId();
        PayableTransaction transaction = checkoutApi.transactionForBuyer(transactionId, buyerId)
                .orElseThrow(PaymentException.TransactionNotFound::new);
        if (transaction.isOnPlan()) {
            return start(transaction, buyerId, instalmentDue(transaction, balance));
        }
        if (balance) {
            throw new PaymentException.NothingToPayOff();
        }
        if (!transaction.isPendingPayment()) {
            throw new PaymentException.TransactionNotPayable(transaction.status());
        }
        // An instalment purchase pays its deposit first (phase 3a); its
        // instalments come with phase 3b. Milestone plans aren't payable yet,
        // nor an instalment purchase opened before plans were declared.
        if (!transaction.isOutright() && !(transaction.isInstalment() && transaction.amountDue() != null)) {
            throw new PaymentException.PlanNotSupported(transaction.plan());
        }
        // OL: no money before the buyer has accepted the offer letter. Purchases
        // opened before offer letters existed have none, and stay payable.
        if (checkoutApi.offerFor(transactionId).filter(offer -> !offer.accepted()).isPresent()) {
            throw new PaymentException.OfferNotAccepted();
        }
        return start(transaction, buyerId, new Due(transaction.amountDue(),
                transaction.isInstalment() ? PaymentPurpose.DEPOSIT : PaymentPurpose.OUTRIGHT, null));
    }

    /**
     * 3b, decision 7: one instalment at a time — the next only once the
     * current one is verified — or the whole balance in one payment.
     */
    private Due instalmentDue(PayableTransaction transaction, boolean balance) {
        PlanSchedule schedule = checkoutApi.schedulesOf(List.of(transaction.id())).get(transaction.id());
        if (schedule == null) {
            throw new PaymentException.PlanNotSupported(transaction.plan());
        }
        PlanProgress progress = PlanProgress.of(schedule, payments.findAllByTransactionIdOrderByCreatedAtAsc(
                transaction.id()));
        if (progress.awaitingFinance().isPresent()) {
            throw new PaymentException.InstalmentAwaitingVerification();
        }
        if (progress.complete()) {
            throw new PaymentException.TransactionNotPayable("paid in full");
        }
        return balance ? new Due(progress.balance(), PaymentPurpose.BALANCE, null)
                : progress.next().map(i -> new Due(i.amount(), PaymentPurpose.INSTALMENT, i.sequence())).orElseThrow();
    }

    private Started start(PayableTransaction transaction, UUID buyerId, Due due) {
        UUID transactionId = transaction.id();
        // NGN only, above zero, whole kobo — refused here before Paystack is ever called.
        long amountKobo = PaystackAmounts.toKobo(due.amount(), transaction.currency());

        // The open link is reused only for the same thing at the same amount.
        Optional<Payment> open = payments.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(
                transactionId, PaymentStatus.INITIALIZED)
                .filter(p -> p.getPurpose() == due.purpose() && p.getAmountKobo() == amountKobo
                        && Objects.equals(p.getInstalmentSequence(), due.instalmentSequence()));
        if (open.isPresent() && open.get().getCreatedAt().plus(linkReuseWindow).isAfter(Instant.now())) {
            return new Started(toDto(open.get()), false);
        }

        String email = identityApi.emailOf(buyerId).orElseThrow(PaymentException.TransactionNotFound::new);
        // Saved first so the database assigns its id, which goes into Paystack's
        // metadata. If Paystack then fails, this transaction rolls back and the
        // row disappears with it — nothing is left half-made.
        Payment payment = payments.saveAndFlush(Payment.builder()
                .transactionId(transaction.id())
                .buyerUserId(buyerId)
                .sellerTenantId(transaction.sellerTenantId())
                .reference("LV-PAY-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT))
                .amount(PaystackAmounts.fromKobo(amountKobo))
                .currency(transaction.currency())
                .amountKobo(amountKobo)
                .status(PaymentStatus.INITIALIZED)
                .purpose(due.purpose())
                .instalmentSequence(due.instalmentSequence())
                .build());
        String reference = payment.getReference();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("paymentId", payment.getId().toString());
        metadata.put("transactionId", transaction.id().toString());
        metadata.put("transactionReference", transaction.reference());
        metadata.put("reservationId", transaction.reservationId().toString());
        metadata.put("plotId", transaction.plotId().toString());

        PaystackClient.InitializeResult link = paystack.initialize(new PaystackClient.InitializeRequest(
                email, amountKobo, reference, paystackProperties.callbackUrl(), metadata));

        payment.setAuthorizationUrl(link.authorizationUrl());
        payment.setAccessCode(link.accessCode());
        payment = payments.saveAndFlush(payment);

        auditApi.record(AuditEntryRequest.of(buyerId, "payment.initialized", "payment", payment.getId(),
                transaction.sellerTenantId(), "Payment " + reference + " started for transaction "
                        + transaction.reference() + ": ₦" + payment.getAmount().toPlainString() + " via Paystack."));
        log.info("Payment {} initialized for transaction {}", reference, transaction.id());
        return new Started(toDto(payment), true);
    }

    static PaymentDto toDto(Payment p) {
        return new PaymentDto(p.getId(), p.getTransactionId(), p.getReference(), p.getStatus().wire(), p.getAmount(),
                p.getCurrency().name(), p.getAuthorizationUrl(), p.getCreatedAt(), p.getGatewayResponse(),
                p.getChannel(), p.getPaidAt(), p.getReviewReason() != null);
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
