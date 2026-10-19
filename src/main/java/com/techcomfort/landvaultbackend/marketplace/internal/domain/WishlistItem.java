package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.marketplace.internal.enums.WishlistListingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A listing a buyer saved (changeset 096). Buyer-owned, never changed after
 * saving, hard-deleted on removal. See AGENTS.md, "The buyer's wishlist".
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "wishlist_items")
public class WishlistItem extends AbstractEntity {

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "listing_type", nullable = false, length = 16, updatable = false)
    private WishlistListingType listingType;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    /** Shown once the listing has left the marketplace; the live name is used while it's listed. */
    @Column(name = "name_at_save", nullable = false, updatable = false)
    private String nameAtSave;

    @Column(name = "price_at_save", updatable = false)
    private BigDecimal priceAtSave;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3, updatable = false)
    private Currency currency;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private Instant savedAt;
}
