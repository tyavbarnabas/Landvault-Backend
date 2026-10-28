package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.PaymentDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentPenalty;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackAmounts;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackProperties;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentPenaltyRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
    private final PaymentPenaltyRepository penalties;
    private final PlanStandings planStandings;

    /** How long an unpaid link is handed back again instead of opening a second payment. */
    @Value("${landvault.payments.link-reuse-window:30m}")
    private Duration linkReuseWindow;

    /** A payment attempt, and whether it was just created or an open one handed back. */
    public record Started(PaymentDto payment, boolean created) {
    }

    /** What this payment is for, and how much: the instalment(s) plus any penalty lines. */
    private record Due(BigDecimal amount, PaymentPurpose purpose, Integer instalmentSequence,
                       List<PenaltyLine> penalties) {

        Due(BigDecimal amount, PaymentPurpose purpose, Integer instalmentSequence) {
            this(amount, purpose, instalmentSequence, List.of());
        }

        /** Whole kobo, two decimals — the same scale as the payment's own amount. */
        BigDecimal penaltyTotal() {
            return penalties.stream().map(l -> l.penalty().amount()).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.UNNECESSARY);
        }

        BigDecimal total() {
            return amount.add(penaltyTotal());
        }
    }

    private record PenaltyLine(int sequence, LatePaymentTerms.Penalty penalty) {
    }

    /**
     * Starts the next payment: the full price, a plan's deposit, or — once the
     * deposit is verified (3b) — the next instalment, or with {@code balance}
     * everything still owed. Always from the fixed schedule, never the request.
     */
    @Transactional
    public Started start(UUID transactionId, boolean balance) {
        return start(transactionId, balance, null);
    }

    /**
     * As {@link #start(UUID, boolean)}; {@code amount} is a flexible plan's
     * contribution, the one figure a buyer names (phase 3a: the first, at
     * least the minimum to start and at most the plan total). Refused on any
     * other plan, where the amount always comes from the purchase.
     */
    @Transactional
    public Started start(UUID transactionId, boolean balance, BigDecimal amount) {
        UUID buyerId = currentScope().userId();
        PayableTransaction transaction = checkoutApi.transactionForBuyer(transactionId, buyerId)
                .orElseThrow(PaymentException.TransactionNotFound::new);
        if (amount != null && !transaction.isFlexible()) {
            throw new PaymentException.InvalidAmount(
                    "The amount comes from your purchase; only a flexible plan lets you choose it.");
        }
        if (transaction.isOnPlan()) {
            return start(transaction, buyerId, transaction.isFlexible()
                    ? new Due(laterContribution(transaction, balance, amount), PaymentPurpose.CONTRIBUTION, null)
                    : instalmentDue(transaction, balance));
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
        if (!transaction.isOutright() && !transaction.isFlexible()
                && !(transaction.isInstalment() && transaction.amountDue() != null)) {
            throw new PaymentException.PlanNotSupported(transaction.plan());
        }
        // OL: no money before the buyer has accepted the offer letter. Purchases
        // opened before offer letters existed have none, and stay payable.
        if (checkoutApi.offerFor(transactionId).filter(offer -> !offer.accepted()).isPresent()) {
            throw new PaymentException.OfferNotAccepted();
        }
        if (transaction.isFlexible()) {
            return start(transaction, buyerId, new Due(firstContribution(transaction, amount),
                    PaymentPurpose.CONTRIBUTION, null));
        }
        return start(transaction, buyerId, new Due(transaction.amountDue(),
                transaction.isInstalment() ? PaymentPurpose.DEPOSIT : PaymentPurpose.OUTRIGHT, null));
    }

    /**
     * 3b, decision 7: one instalment at a time — the next only once the
     * current one is verified — or the whole balance in one payment. Phase 4:
     * an overdue instalment carries the penalty it has reached today, fixed
     * now, as its own line; a pay-off carries every overdue instalment's.
     */
    private Due instalmentDue(PayableTransaction transaction, boolean balance) {
        PlanSchedule schedule = checkoutApi.schedulesOf(List.of(transaction.id())).get(transaction.id());
        if (schedule == null) {
            throw new PaymentException.PlanNotSupported(transaction.plan());
        }
        // Through PlanStandings, so the penalty charged is net of any waiver — the same figure the buyer sees.
        PlanProgress progress = planStandings.progressOf(Map.of(transaction.id(), schedule)).get(transaction.id());
        if (progress.awaitingFinance().isPresent()) {
            throw new PaymentException.InstalmentAwaitingVerification(false);
        }
        if (progress.complete()) {
            throw new PaymentException.TransactionNotPayable("paid in full");
        }
        LocalDate today = PlanStandings.today();
        if (balance) {
            return new Due(progress.balance(), PaymentPurpose.BALANCE, null, progress.remaining().stream()
                    .flatMap(i -> progress.penaltyDue(i, today).map(p -> new PenaltyLine(i.sequence(), p)).stream())
                    .toList());
        }
        PlanSchedule.Instalment next = progress.next().orElseThrow();
        return new Due(next.amount(), PaymentPurpose.INSTALMENT, next.sequence(),
                progress.penaltyDue(next, today).map(p -> List.of(new PenaltyLine(next.sequence(), p))).orElse(List.of()));
    }

    /**
     * Flexible contribution, phase 3a: the first payment is the buyer's own
     * figure, at least the minimum to start and at most the plan total. Its
     * kobo is checked when it is converted for Paystack.
     */
    private static BigDecimal firstContribution(PayableTransaction transaction, BigDecimal amount) {
        FlexiblePurchase plan = transaction.flexible();
        String limits = "at least " + naira(plan.minimumToStart()) + " and at most " + naira(plan.planTotal());
        if (amount == null) {
            throw new PaymentException.InvalidAmount("Choose how much to pay first (amount): " + limits + ".");
        }
        if (amount.compareTo(plan.minimumToStart()) < 0 || amount.compareTo(plan.planTotal()) > 0) {
            throw new PaymentException.InvalidAmount("Your first payment must be " + limits + ".");
        }
        return amount;
    }

    /**
     * Flexible contribution, phase 3b: any amount up to what's left — at least
     * the declared minimum contribution unless it finishes the plan — or, with
     * {@code balance}, exactly what's left. One payment waits for finance at a
     * time, as on an instalment plan (decision 1).
     */
    private BigDecimal laterContribution(PayableTransaction transaction, boolean balance, BigDecimal amount) {
        FlexibleProgress progress = FlexibleProgress.of(transaction.flexible(),
                payments.findAllByTransactionIdOrderByCreatedAtAsc(transaction.id()));
        if (progress.awaitingFinance().isPresent()) {
            throw new PaymentException.InstalmentAwaitingVerification(true);
        }
        if (progress.complete()) {
            throw new PaymentException.TransactionNotPayable("paid in full");
        }
        BigDecimal remaining = progress.remaining();
        if (balance) {
            if (amount != null && amount.compareTo(remaining) != 0) {
                throw new PaymentException.InvalidAmount("balance=true pays what's left, " + naira(remaining)
                        + "; leave amount out, or send that figure.");
            }
            return remaining;
        }
        BigDecimal minimum = transaction.flexible().minimumContribution();
        // What's left may be below the minimum contribution: then finishing is the only payment.
        String limits = minimum == null ? "up to " + naira(remaining)
                : minimum.compareTo(remaining) >= 0 ? "exactly " + naira(remaining) + ", what's left"
                : "at least " + naira(minimum) + " and at most " + naira(remaining);
        if (amount == null) {
            throw new PaymentException.InvalidAmount("Choose how much to pay (amount): " + limits + ".");
        }
        if (!progress.allows(amount)) {
            throw new PaymentException.InvalidAmount("This payment must be " + limits + ".");
        }
        return amount;
    }

    private static String naira(BigDecimal amount) {
        return "₦" + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.UK)).format(amount);
    }

    private Started start(PayableTransaction transaction, UUID buyerId, Due due) {
        UUID transactionId = transaction.id();
        // NGN only, above zero, whole kobo — refused here before Paystack is ever called.
        long amountKobo = PaystackAmounts.toKobo(due.total(), transaction.currency());

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
                .penaltyAmount(due.penaltyTotal())
                .build());
        UUID paymentId = payment.getId();
        penalties.saveAll(due.penalties().stream().map(line -> PaymentPenalty.builder()
                .paymentId(paymentId)
                .instalmentSequence(line.sequence())
                .monthsLate(line.penalty().monthsLate())
                .penaltyPct(line.penalty().penaltyPct())
                .amount(line.penalty().amount())
                .build()).toList());
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
                        + transaction.reference() + ": ₦" + payment.getAmount().toPlainString()
                        + (due.penalties().isEmpty() ? "" : " (including ₦" + due.penaltyTotal().toPlainString()
                        + " late-payment penalty)") + " via Paystack."));
        log.info("Payment {} initialized for transaction {}", reference, transaction.id());
        return new Started(toDto(payment), true);
    }

    static PaymentDto toDto(Payment p) {
        return new PaymentDto(p.getId(), p.getTransactionId(), p.getReference(), p.getStatus().wire(), p.getAmount(),
                p.getCurrency().name(), p.getAuthorizationUrl(), p.getCreatedAt(), p.getGatewayResponse(),
                p.getChannel(), p.getPaidAt(), p.getReviewReason() != null, p.getPenaltyAmount());
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
