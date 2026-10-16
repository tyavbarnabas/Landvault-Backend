package com.techcomfort.landvaultbackend.documents.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment receipt and a copy of everything printed on it (changeset 089). Never updated. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "receipts")
public class Receipt extends AbstractAppendOnlyEntity {

    @Column(name = "number", nullable = false, updatable = false)
    private String number;

    @Column(name = "verification_code", nullable = false, updatable = false)
    private String verificationCode;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "buyer_name", nullable = false, updatable = false)
    private String buyerName;

    @Column(name = "seller_name", nullable = false, updatable = false)
    private String sellerName;

    @Column(name = "seller_rc_number", updatable = false)
    private String sellerRcNumber;

    @Column(name = "estate_name", nullable = false, updatable = false)
    private String estateName;

    @Column(name = "estate_location", updatable = false)
    private String estateLocation;

    @Column(name = "block_name", updatable = false)
    private String blockName;

    @Column(name = "plot_number", nullable = false, updatable = false)
    private String plotNumber;

    @Column(name = "plot_size_sqm", updatable = false)
    private BigDecimal plotSizeSqm;

    @Column(name = "is_corner", nullable = false, updatable = false)
    private boolean corner;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(name = "channel", updatable = false)
    private String channel;

    @Column(name = "last4", updatable = false)
    private String last4;

    @Column(name = "payment_reference", nullable = false, updatable = false)
    private String paymentReference;

    @Column(name = "paid_at", updatable = false)
    private Instant paidAt;

    /** "Block A, Plot 7", or just "Plot 7" in an estate without blocks. */
    public String plotLabel() {
        return blockName == null || blockName.isBlank() ? "Plot " + plotNumber
                : "Block " + blockName + ", Plot " + plotNumber;
    }
}
