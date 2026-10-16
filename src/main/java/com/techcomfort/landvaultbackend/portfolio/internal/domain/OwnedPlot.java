package com.techcomfort.landvaultbackend.portfolio.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PlotIntent;
import com.techcomfort.landvaultbackend.portfolio.internal.enums.OwnedPlotStatus;
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
 * A plot a buyer owns (changeset 088). Buyer-owned: {@code tenantId} stays
 * null, the seller is {@code sellerTenantId}. Prices are copied from the sale
 * and never recomputed. See AGENTS.md, "Portfolio".
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "owned_plots")
@SQLRestriction("deleted = false")
public class OwnedPlot extends AbstractEntity {

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    /** The sale this came from. Null only for a record a future upgrade creates. */
    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OwnedPlotStatus status;

    /** The checkout's plan constant ({@code OUTRIGHT}, ...), as stored on the sale. */
    @Column(name = "plan", nullable = false, length = 16, updatable = false)
    private String plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, length = 16, updatable = false)
    private PlotIntent intent;

    @Column(name = "base_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal basePrice;

    @Column(name = "corner_premium_pct", precision = 5, scale = 2, updatable = false)
    private BigDecimal cornerPremiumPct;

    @Column(name = "total_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private Currency currency;

    /** When finance verified the sale. */
    @Column(name = "acquired_at", nullable = false, updatable = false)
    private Instant acquiredAt;

    /** The record this one replaced, set by a future upgrade (which marks that one superseded). */
    @Column(name = "supersedes", updatable = false)
    private UUID supersedes;
}
