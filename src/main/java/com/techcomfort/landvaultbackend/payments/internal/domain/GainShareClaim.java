package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.enums.GainShareStatus;
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
 * Flexible contribution, phase 5: a buyer's claim on the gain of the plot's
 * next completed sale, after they left their flexible plan or it was ended
 * (changeset 114). Buyer-owned like the purchase: not RLS-policied. The
 * figures it was opened with are copied from the purchase and never change.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "gain_share_claims")
@SQLRestriction("deleted = false")
public class GainShareClaim extends AbstractEntity {

    /** The purchase the buyer left (withdrawn) or that was ended (revoked). */
    @Column(name = "claim_transaction_id", nullable = false, updatable = false)
    private UUID claimTransactionId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    /** The buyer's agreed land price: the gain is measured from it. */
    @Column(name = "agreed_price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal agreedPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    /** The share of any gain the developer keeps, as offered when the purchase opened. */
    @Column(name = "developer_share_pct", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal developerSharePct;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GainShareStatus status;

    /** The completed sale it was settled on, and what it found. */
    @Column(name = "sale_transaction_id")
    private UUID saleTransactionId;

    @Column(name = "sale_price", precision = 19, scale = 4)
    private BigDecimal salePrice;

    @Column(name = "gain", precision = 19, scale = 2)
    private BigDecimal gain;

    /** What the buyer is owed, paid as payout tranche {@code GAIN:<id>} on the sale. */
    @Column(name = "buyer_amount", precision = 19, scale = 2)
    private BigDecimal buyerAmount;

    @Column(name = "settled_at")
    private Instant settledAt;

    /** The account the buyer gave; the name is the bank's, never typed. */
    @Column(name = "account_bank_code", length = 16)
    private String accountBankCode;

    @Column(name = "account_bank_name", length = 120)
    private String accountBankName;

    @Column(name = "account_number", length = 10)
    private String accountNumber;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "recipient_code", length = 64)
    private String recipientCode;

    @Column(name = "account_given_at")
    private Instant accountGivenAt;

    /** The payout tranche key this claim is paid as. */
    public String trancheKey() {
        return "GAIN:" + getId();
    }
}
