package com.techcomfort.landvaultbackend.documents.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An issued offer letter (OL): a copy of everything it prints, the price and
 * the setup versions it quoted (changeset 098). Never updated; acceptance is
 * {@link OfferLetterAcceptance}.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "offer_letters")
public class OfferLetter extends AbstractAppendOnlyEntity {

    @Column(name = "number", nullable = false, updatable = false)
    private String number;

    @Column(name = "verification_code", nullable = false, updatable = false)
    private String verificationCode;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "seller_tenant_id", nullable = false, updatable = false)
    private UUID sellerTenantId;

    @Column(name = "branch_id", updatable = false)
    private UUID branchId;

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "company_name", nullable = false, updatable = false)
    private String companyName;

    @Column(name = "company_rc_number", updatable = false)
    private String companyRcNumber;

    @Column(name = "company_address", updatable = false)
    private String companyAddress;

    @Column(name = "company_phone", updatable = false)
    private String companyPhone;

    @Column(name = "company_email", updatable = false)
    private String companyEmail;

    @Column(name = "company_website", updatable = false)
    private String companyWebsite;

    @Column(name = "logo_file_id", updatable = false)
    private UUID logoFileId;

    @Column(name = "signature_file_id", nullable = false, updatable = false)
    private UUID signatureFileId;

    @Column(name = "seal_file_id", updatable = false)
    private UUID sealFileId;

    @Column(name = "signatory_name", nullable = false, updatable = false)
    private String signatoryName;

    @Column(name = "signatory_title", nullable = false, updatable = false)
    private String signatoryTitle;

    @Column(name = "buyer_name", nullable = false, updatable = false)
    private String buyerName;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "buyer_lines", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] buyerLines;

    @Column(name = "salutation", nullable = false, updatable = false)
    private String salutation;

    @Column(name = "estate_name", nullable = false, updatable = false)
    private String estateName;

    @Column(name = "estate_location", updatable = false)
    private String estateLocation;

    @Column(name = "plot_label", nullable = false, updatable = false)
    private String plotLabel;

    @Column(name = "opening", nullable = false, updatable = false)
    private String opening;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "clauses", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] clauses;

    /** The price and total commitment, printed first; kept apart so the allocation letter can copy {@link #clauses}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "price_clauses", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] priceClauses;

    @Column(name = "closing", nullable = false, updatable = false)
    private String closing;

    @Column(name = "sign_off", nullable = false, updatable = false)
    private String signOff;

    @Column(name = "base_price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal basePrice;

    @Column(name = "corner_premium_pct", updatable = false, precision = 5, scale = 2)
    private BigDecimal cornerPremiumPct;

    @Column(name = "total_price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal totalPrice;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "approval_id", nullable = false, updatable = false)
    private UUID approvalId;

    @Column(name = "terms_id", nullable = false, updatable = false)
    private UUID termsId;

    @Column(name = "terms_version", nullable = false, updatable = false)
    private Integer termsVersion;

    @Column(name = "template_source", nullable = false, updatable = false)
    private String templateSource;

    @Column(name = "template_version", updatable = false)
    private Integer templateVersion;

    @Column(name = "company_letterhead_version", nullable = false, updatable = false)
    private Integer companyLetterheadVersion;

    @Column(name = "branch_letterhead_version", updatable = false)
    private Integer branchLetterheadVersion;

    @Column(name = "fees_version", updatable = false)
    private Integer feesVersion;

    @Column(name = "refund_terms_version", updatable = false)
    private Integer refundTermsVersion;

    @Column(name = "default_terms_version", updatable = false)
    private Integer defaultTermsVersion;
}
