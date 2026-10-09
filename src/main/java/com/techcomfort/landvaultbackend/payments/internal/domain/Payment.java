package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
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

/** One payment attempt against a transaction (changeset 070). Only a card's last4 and BIN are ever kept. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "payments")
@SQLRestriction("deleted = false")
public class Payment extends AbstractEntity {

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Column(name = "seller_tenant_id", nullable = false)
    private UUID sellerTenantId;

    @Column(name = "reference", nullable = false, unique = true)
    private String reference;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Column(name = "amount_kobo", nullable = false)
    private Long amountKobo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "authorization_url")
    private String authorizationUrl;

    @Column(name = "access_code")
    private String accessCode;

    @Column(name = "gateway_response")
    private String gatewayResponse;

    @Column(name = "channel")
    private String channel;

    /** Last 4 digits of what paid: a card, or the paying account for a bank transfer (see {@link #channel}). */
    @Column(name = "last4")
    private String last4;

    @Column(name = "card_bin")
    private String cardBin;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Set when finance must look at this payment by hand; null otherwise. */
    @Column(name = "review_reason")
    private String reviewReason;

    /** When this payment was found to be owed back to the buyer; null when nothing is owed. */
    @Column(name = "refund_requested_at")
    private Instant refundRequestedAt;
}
