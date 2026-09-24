package com.techcomfort.landvaultbackend.checkout.internal.domain;

import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
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
import java.time.Instant;
import java.util.UUID;

/**
 * A 45-minute hold on one plot. Buyer-owned: the inherited
 * {@code tenantId}/{@code branchId} stay null and the seller is carried by
 * the explicit {@code sellerTenantId} instead. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "reservations")
@SQLRestriction("deleted = false")
public class Reservation extends AbstractEntity {

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    /**
     * Which company's plot this is. Deliberately <em>not</em> the inherited
     * {@code tenantId}: that column means "this row is isolated to that
     * tenant", and a reservation belongs to a buyer who has no tenant at all.
     */
    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    /** The tier the plot was priced by when the hold was taken. */
    @Column(name = "price_tier_id", nullable = false, updatable = false)
    private UUID priceTierId;

    /**
     * The price as it stood when the plot was locked — captured here, not
     * at transaction time, because this is the moment the buyer acted on.
     * A tier re-priced during the 45 minutes does not move these figures.
     */
    @Column(name = "base_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal basePrice;

    /** Null on a non-corner plot, never zero. */
    @Column(name = "corner_premium_pct", precision = 5, scale = 2, updatable = false)
    private BigDecimal cornerPremiumPct;

    @Column(name = "total_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private Currency currency;

    /**
     * What the plot was before the hold, restored when it ends —
     * {@code AVAILABLE_DEV} and {@code AVAILABLE_INV} are a real
     * distinction, so a released development plot must not come back as an
     * investment one.
     */
    @Column(name = "previous_plot_status", nullable = false, length = 32)
    private String previousPlotStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** When the hold stopped being active, however it ended. */
    @Column(name = "ended_at")
    private Instant endedAt;
}
