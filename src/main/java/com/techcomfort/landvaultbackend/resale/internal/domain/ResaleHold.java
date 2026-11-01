package com.techcomfort.landvaultbackend.resale.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.resale.internal.enums.HoldEndReason;
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
 * Sell my plot, phase 5a: a new buyer's hold on a resale listing (changeset
 * 119), with the price and the split fixed when it was taken (D25). The hold
 * itself is checkout's reservation; this is the resale module's record of it.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "resale_holds")
@SQLRestriction("deleted = false")
public class ResaleHold extends AbstractEntity {

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "seller_transaction_id", nullable = false, updatable = false)
    private UUID sellerTransactionId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "held_at", nullable = false, updatable = false)
    private Instant heldAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 16)
    private HoldEndReason endReason;

    @Column(name = "price", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "early_sale", nullable = false, updatable = false)
    private boolean earlySale;

    @Column(name = "developer_balance", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal developerBalance;

    @Column(name = "penalties_owed", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal penaltiesOwed;

    @Column(name = "gain", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal gain;

    @Column(name = "developer_gain_share_pct", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal developerGainSharePct;

    @Column(name = "developer_gain_share", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal developerGainShare;

    @Column(name = "transfer_fee", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal transferFee;

    @Column(name = "developer_receives", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal developerReceives;

    @Column(name = "seller_receives", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal sellerReceives;

    public boolean isOpen() {
        return endedAt == null;
    }
}
