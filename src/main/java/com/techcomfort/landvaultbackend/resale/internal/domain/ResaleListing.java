package com.techcomfort.landvaultbackend.resale.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingKind;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
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
 * Sell my plot, phase 2: a buyer's listing of a plot they own or are paying
 * for (changeset 116). Buyer-owned like the purchase: not RLS-policied. An
 * early sale stores no price — it is always the developer's current one.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "resale_listings")
@SQLRestriction("deleted = false")
public class ResaleListing extends AbstractEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "seller_user_id", nullable = false, updatable = false)
    private UUID sellerUserId;

    /** The developer whose estate it is. */
    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private ListingKind kind;

    @Column(name = "early_sale", nullable = false, updatable = false)
    private boolean earlySale;

    /** The seller's price; null on an early sale. */
    @Column(name = "asking_price", precision = 19, scale = 2)
    private BigDecimal askingPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ListingStatus status;

    @Column(name = "listed_at", nullable = false, updatable = false)
    private Instant listedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Why a rule took it down (ENDED only): one of the eligibility reason codes. */
    @Column(name = "close_reason", length = 40)
    private String closeReason;

    /** The first listing on a flexible plan stops the inactivity clock while it is up. */
    @Column(name = "pauses_inactivity", nullable = false, updatable = false)
    private boolean pausesInactivity;

    public boolean isLive() {
        return status == ListingStatus.LISTED;
    }
}
