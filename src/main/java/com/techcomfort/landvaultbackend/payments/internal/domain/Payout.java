package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.enums.PayoutStatus;
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

/** One payout attempt for a verified sale (changeset 076). Never overwritten — a retry is a new row. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "payouts")
@SQLRestriction("deleted = false")
public class Payout extends AbstractEntity {

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "settlement_account_id", nullable = false)
    private UUID settlementAccountId;

    @Column(name = "recipient_code", nullable = false, length = 64)
    private String recipientCode;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Column(name = "amount_kobo", nullable = false)
    private Long amountKobo;

    @Column(name = "reference", nullable = false, unique = true, length = 64)
    private String reference;

    @Column(name = "transfer_code", length = 64)
    private String transferCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PayoutStatus status;

    @Column(name = "gateway_message", length = 500)
    private String gatewayMessage;

    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "finalized_by")
    private UUID finalizedBy;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    /** Which of {@link #partCount} equal pieces of the sale this transfer is (1-based). */
    @Column(name = "part_number", nullable = false)
    private Integer partNumber;

    @Column(name = "part_count", nullable = false)
    private Integer partCount;

    @Column(name = "transferred_at")
    private Instant transferredAt;

    /** Why a person must look at this payout; null when nothing to review. */
    @Column(name = "review_reason", length = 500)
    private String reviewReason;
}
