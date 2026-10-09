package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
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

/** Money returned to a buyer for one payment (changeset 080). The payment itself stays succeeded. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "refunds")
@SQLRestriction("deleted = false")
public class Refund extends AbstractEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Column(name = "seller_tenant_id", nullable = false)
    private UUID sellerTenantId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Column(name = "amount_kobo", nullable = false)
    private Long amountKobo;

    @Column(name = "paystack_refund_id", length = 64)
    private String paystackRefundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RefundStatus status;

    @Column(name = "gateway_message", length = 500)
    private String gatewayMessage;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "expected_at")
    private Instant expectedAt;

    @Column(name = "account_bank_code", length = 16)
    private String accountBankCode;

    @Column(name = "account_bank_name")
    private String accountBankName;

    @Column(name = "account_number", length = 10)
    private String accountNumber;

    /** What the bank returned for the buyer's account, never typed. */
    @Column(name = "account_name")
    private String accountName;

    @Column(name = "account_submitted_at")
    private Instant accountSubmittedAt;

    @Column(name = "account_sent_by")
    private UUID accountSentBy;

    @Column(name = "account_sent_at")
    private Instant accountSentAt;
}
