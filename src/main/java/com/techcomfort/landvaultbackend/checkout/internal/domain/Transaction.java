package com.techcomfort.landvaultbackend.checkout.internal.domain;

import com.techcomfort.landvaultbackend.checkout.internal.enums.PaymentPlan;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.common.PlotIntent;
import com.techcomfort.landvaultbackend.common.PostalAddress;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * A pending purchase, created when a plot is held. The price fields are
 * captured at that moment and never recomputed — see AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "transactions")
@SQLRestriction("deleted = false")
public class Transaction extends AbstractEntity {

    /** Human-quotable on a transfer or a support call, which a uuid is not. */
    @Column(name = "reference", nullable = false, updatable = false, length = 32)
    private String reference;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    /** The selling company — not an isolation scope; see {@link Reservation}. */
    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    /** The tier's own price at reservation, before any corner premium. */
    @Column(name = "base_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal basePrice;

    /** Null on a non-corner plot, never zero — a premium that wasn't applied must not read as though it was. */
    @Column(name = "corner_premium_pct", precision = 5, scale = 2, updatable = false)
    private BigDecimal cornerPremiumPct;

    @Column(name = "total_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private Currency currency;

    /** What the buyer means to do with the land — not the seller's listing intent. */
    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, length = 16)
    private PlotIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 16)
    private PaymentPlan plan;

    @Column(name = "installment_months")
    private Integer installmentMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TransactionStatus status;

    // The buyer's address as it stood when this purchase was opened, copied
    // from their account and never changed afterwards — the allocation letter
    // prints it. Null on purchases opened before changeset 097.
    @Column(name = "buyer_address_line1", updatable = false)
    private String buyerAddressLine1;

    @Column(name = "buyer_address_line2", updatable = false)
    private String buyerAddressLine2;

    @Column(name = "buyer_address_city", updatable = false)
    private String buyerAddressCity;

    @Column(name = "buyer_address_state", updatable = false)
    private String buyerAddressState;

    @Column(name = "buyer_address_postal_code", updatable = false)
    private String buyerAddressPostalCode;

    @Column(name = "buyer_address_country", length = 2, updatable = false)
    private String buyerAddressCountry;

    // IC-1: the declared plan an instalment purchase was opened on, and its
    // figures as worked out then — never recomputed (the price rule).
    @Column(name = "plan_id", updatable = false)
    private UUID planId;

    @Column(name = "plan_name", updatable = false)
    private String planName;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_frequency", updatable = false, length = 16)
    private PaymentFrequency planFrequency;

    @Column(name = "plan_total", updatable = false, precision = 19, scale = 4)
    private BigDecimal planTotal;

    @Column(name = "plan_premium", updatable = false, precision = 19, scale = 4)
    private BigDecimal planPremium;

    @Column(name = "deposit_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal depositAmount;

    /**
     * 3c escrow: the refund deduction offered when an instalment purchase
     * opened — the developer's share of each payment; the rest is held until
     * the plan completes. Both null: everything is held.
     */
    @Column(name = "escrow_deduction_pct", updatable = false, precision = 5, scale = 2)
    private BigDecimal escrowDeductionPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "escrow_applies_to", updatable = false, length = 16)
    private RefundAppliesTo escrowAppliesTo;

    /** Empty on purchases opened before addresses were collected. */
    public Optional<PostalAddress> buyerAddress() {
        if (buyerAddressLine1 == null) {
            return Optional.empty();
        }
        return Optional.of(new PostalAddress(buyerAddressLine1, buyerAddressLine2, buyerAddressCity,
                buyerAddressState, buyerAddressPostalCode, buyerAddressCountry));
    }
}
