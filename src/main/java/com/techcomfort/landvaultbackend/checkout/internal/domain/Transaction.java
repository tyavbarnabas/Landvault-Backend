package com.techcomfort.landvaultbackend.checkout.internal.domain;

import com.techcomfort.landvaultbackend.checkout.internal.enums.PaymentPlan;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PlotIntent;
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
}
