package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.GainShareDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.GainShareClaim;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payout;
import com.techcomfort.landvaultbackend.payments.internal.enums.GainShareStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.PayoutStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackBankDirectory;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.GainShareClaimRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Flexible contribution, phase 5 (decided with the user, 2026-10-27): a buyer
 * who left a flexible plan — or whose quiet plan was ended — keeps a claim on
 * the plot. When its next sale through HomeTrue completes (fully paid and
 * verified by finance), the gain is worked out agreed price to agreed price,
 * and the buyer's share is taken off the developer's payout for that sale and
 * paid to the buyer as payout tranche {@code GAIN:<claim id>}. No time limit.
 * Several open claims are settled oldest first, never more in total than the
 * developer is owed from that sale. See AGENTS.md, "Flexible contribution".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GainShareService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final GainShareClaimRepository claims;
    private final PaymentRepository payments;
    private final PayoutRepository payouts;
    private final CheckoutApi checkoutApi;
    private final PaystackBankDirectory bankDirectory;
    private final PaystackClient paystack;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;

    /**
     * The buyer's flexible plan just ended (left, or ended as quiet): open their
     * claim, in the same transaction. None when the developer keeps every gain.
     */
    void open(PayableTransaction purchase, UUID actor) {
        if (!purchase.isFlexible() || claims.existsByClaimTransactionId(purchase.id())) {
            return;
        }
        BigDecimal developerShare = purchase.flexible().developerGainSharePct();
        if (developerShare.compareTo(HUNDRED) >= 0) {
            return;
        }
        GainShareClaim claim = claims.saveAndFlush(GainShareClaim.builder()
                .claimTransactionId(purchase.id())
                .plotId(purchase.plotId())
                .buyerUserId(purchase.buyerUserId())
                .sellerTenantId(purchase.sellerTenantId())
                .agreedPrice(purchase.totalPrice())
                .currency(purchase.currency())
                .developerSharePct(developerShare)
                .openedAt(Instant.now())
                .status(GainShareStatus.OPEN)
                .build());
        auditApi.record(AuditEntryRequest.of(actor, "gain_share.opened", "gain_share_claim", claim.getId(),
                purchase.sellerTenantId(), "Gain claim opened for the buyer of " + purchase.reference() + ": "
                        + HUNDRED.subtract(developerShare).stripTrailingZeros().toPlainString() + "% of any gain above "
                        + purchase.totalPrice().stripTrailingZeros().toPlainString() + " on the plot's next sale."));
    }

    /**
     * A sale just completed — fully paid and verified by finance — in the
     * caller's transaction. Settles the plot's open claims against it, oldest
     * first, capped at what the developer would be owed from its whole-price
     * tranche (outright) or release (a plan). Nothing happens without claims.
     */
    void settleOn(UUID saleTransactionId, UUID actor) {
        Optional<VerifiedSale> found = checkoutApi.verifiedSale(saleTransactionId);
        if (found.isEmpty() || found.get().plotId() == null) {
            return;
        }
        VerifiedSale sale = found.get();
        List<GainShareClaim> open = claims.findOpenLockedByPlotId(sale.plotId()).stream()
                .filter(c -> !c.getClaimTransactionId().equals(saleTransactionId)).toList();
        if (open.isEmpty()) {
            return;
        }
        List<Payment> salePayments = sale.escrow() == null ? List.of()
                : payments.findAllByTransactionIdOrderByCreatedAtAsc(saleTransactionId);
        BigDecimal cap = PayoutTranches.developerRemainder(sale, salePayments);
        BigDecimal deducted = BigDecimal.ZERO;
        Instant now = Instant.now();
        for (GainShareClaim claim : open) {
            BigDecimal gain = claim.getCurrency() == sale.currency()
                    ? sale.totalPrice().subtract(claim.getAgreedPrice()).setScale(2, RoundingMode.DOWN)
                    : BigDecimal.ZERO;
            BigDecimal share = gain.signum() <= 0 ? BigDecimal.ZERO
                    : gain.multiply(HUNDRED.subtract(claim.getDeveloperSharePct())).divide(HUNDRED, 2, RoundingMode.DOWN);
            BigDecimal amount = share.min(cap.subtract(deducted).max(BigDecimal.ZERO));
            deducted = deducted.add(amount);
            claim.setSaleTransactionId(saleTransactionId);
            claim.setSalePrice(sale.totalPrice());
            claim.setGain(gain.max(BigDecimal.ZERO));
            claim.setSettledAt(now);
            claim.setStatus(amount.signum() > 0 ? GainShareStatus.OWED : GainShareStatus.NO_GAIN);
            claim.setBuyerAmount(amount.signum() > 0 ? amount : null);
            claims.saveAndFlush(claim);
            auditApi.record(AuditEntryRequest.of(actor, "gain_share." + claim.getStatus().wire(), "gain_share_claim",
                    claim.getId(), sale.sellerTenantId(), "Gain claim settled on sale " + sale.transactionReference()
                            + " at " + sale.totalPrice().stripTrailingZeros().toPlainString() + " (agreed "
                            + claim.getAgreedPrice().stripTrailingZeros().toPlainString() + "): "
                            + (amount.signum() > 0 ? amount.toPlainString() + " owed to the earlier buyer"
                            + (amount.compareTo(share) < 0 ? ", capped from " + share.toPlainString() : "") + "."
                            : "nothing owed.")));
            if (amount.signum() > 0) {
                tellOwed(claim, sale, share, amount);
            }
        }
        BigDecimal total = deducted;
        if (total.signum() > 0) {
            for (String to : identityApi.executiveDirectorEmails(sale.sellerTenantId())) {
                buyerNotifier.afterCommit("gain share deduction", () -> alerts.gainShareDeduction(
                        new SettlementAlertSender.GainShareDeductionAlert(to, sale.transactionReference(),
                                sale.plotLabel(), sale.estateName(), naira(sale.totalPrice()), naira(total))));
            }
        }
        log.info("Sale {} settled {} gain claim(s); {} deducted from the developer's payout", saleTransactionId,
                open.size(), deducted);
    }

    /** Claims owed out of these sales' payouts, by sale. */
    Map<UUID, List<GainShareClaim>> owedOn(List<UUID> saleTransactionIds) {
        return saleTransactionIds.isEmpty() ? Map.of()
                : claims.findBySaleTransactionIdInAndStatus(saleTransactionIds, GainShareStatus.OWED).stream()
                .collect(Collectors.groupingBy(GainShareClaim::getSaleTransactionId));
    }

    /** Paystack confirmed a transfer of a gain share: tell the buyer, after commit. */
    void paid(Payout payout) {
        claims.findById(payout.getGainClaimId()).ifPresent(claim -> identityApi.emailOf(claim.getBuyerUserId())
                .ifPresent(to -> buyerNotifier.afterCommit("gain share paid", () -> alerts.gainSharePaid(
                        new SettlementAlertSender.GainSharePaidAlert(to, payout.getAmount().toPlainString(),
                                claim.getAccountBankName(), PayoutRecorder.last4(claim.getAccountNumber()),
                                buyerNotifier.purchaseUrl(claim.getClaimTransactionId()))))));
    }

    // --- the buyer's side ---

    @Transactional(readOnly = true)
    public List<GainShareDto> mine() {
        UUID buyer = currentScope().userId();
        List<GainShareClaim> own = claims.findByBuyerUserIdOrderByOpenedAtDesc(buyer);
        Map<UUID, List<Payout>> bySale = own.stream().anyMatch(c -> c.getSaleTransactionId() != null)
                ? payouts.findAllByTransactionIdInOrderByCreatedAtDesc(own.stream()
                        .map(GainShareClaim::getSaleTransactionId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.groupingBy(Payout::getTransactionId))
                : Map.of();
        return own.stream().map(c -> toDto(c, bySale.getOrDefault(c.getSaleTransactionId(), List.of()))).toList();
    }

    /**
     * The buyer gives the account their owed share goes to. The name is the
     * bank's, never typed; a Paystack recipient is created now. May be replaced
     * until a transfer of it has started.
     */
    @Transactional
    public GainShareDto giveAccount(UUID claimId, String bankCode, String accountNumber) {
        UUID buyer = currentScope().userId();
        GainShareClaim claim = claims.findOwnForUpdate(claimId, buyer).orElseThrow(PaymentException.GainShareNotFound::new);
        if (claim.getStatus() != GainShareStatus.OWED) {
            throw new PaymentException.GainShareNotOwed(claim.getStatus().wire());
        }
        List<Payout> attempts = payouts.findAllLockedByTransactionId(claim.getSaleTransactionId()).stream()
                .filter(p -> claim.getId().equals(p.getGainClaimId())).toList();
        if (attempts.stream().anyMatch(p -> p.getStatus() != PayoutStatus.FAILED && p.getStatus() != PayoutStatus.CANCELLED
                && p.getStatus() != PayoutStatus.REVERSED)) {
            throw new PaymentException.GainShareAlreadySending();
        }
        PaystackClient.Bank bank = bankDirectory.find(bankCode.trim()).orElseThrow(PaymentException.UnknownBank::new);
        String name = paystack.resolveAccountName(accountNumber, bank.code())
                .orElseThrow(PaymentException.AccountNotResolved::new);
        String recipient = paystack.createRecipient(name, accountNumber, bank.code(),
                "HomeTrue gain share " + claim.getId());
        claim.setAccountBankCode(bank.code());
        claim.setAccountBankName(bank.name());
        claim.setAccountNumber(accountNumber);
        claim.setAccountName(name);
        claim.setRecipientCode(recipient);
        claim.setAccountGivenAt(Instant.now());
        claims.saveAndFlush(claim);
        auditApi.record(AuditEntryRequest.of(buyer, "gain_share.account_given", "gain_share_claim", claim.getId(),
                claim.getSellerTenantId(), "Buyer gave " + bank.name() + " ending " + PayoutRecorder.last4(accountNumber)
                        + " (bank name \"" + name + "\") for their gain share."));
        return toDto(claim, attempts);
    }

    private static GainShareDto toDto(GainShareClaim c, List<Payout> salePayouts) {
        BigDecimal paid = salePayouts.stream().filter(p -> c.getId().equals(p.getGainClaimId()))
                .filter(p -> p.getStatus() == PayoutStatus.SUCCESS).map(Payout::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GainShareDto(c.getId(), c.getClaimTransactionId(), c.getStatus().wire(), c.getAgreedPrice(),
                c.getCurrency().name(), HUNDRED.subtract(c.getDeveloperSharePct()), c.getOpenedAt(), c.getSalePrice(),
                c.getGain(), c.getBuyerAmount(), c.getSettledAt(), paid,
                c.getStatus() == GainShareStatus.OWED && c.getRecipientCode() == null,
                c.getAccountNumber() == null ? null : new GainShareDto.Account(c.getAccountBankName(),
                        PayoutRecorder.last4(c.getAccountNumber()), c.getAccountName()));
    }

    // --- telling people ---

    private void tellOwed(GainShareClaim claim, VerifiedSale sale, BigDecimal share, BigDecimal amount) {
        identityApi.emailOf(claim.getBuyerUserId()).ifPresent(to -> buyerNotifier.afterCommit("gain share owed",
                () -> alerts.gainShareOwed(new SettlementAlertSender.GainShareOwedAlert(to, sale.plotLabel(),
                        sale.estateName(), naira(claim.getAgreedPrice()), naira(sale.totalPrice()), naira(claim.getGain()),
                        HUNDRED.subtract(claim.getDeveloperSharePct()).stripTrailingZeros().toPlainString(),
                        naira(amount), amount.compareTo(share) < 0,
                        buyerNotifier.purchaseUrl(claim.getClaimTransactionId())))));
    }

    private static String naira(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
