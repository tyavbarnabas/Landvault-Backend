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

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 5c: an issued restructure letter (HT-RST-): a copy of everything it
 * prints (changeset 108). Never updated; whether it was accepted is the
 * restructure's (payments), asked for through {@code PaymentsApi}.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "restructure_letters")
public class RestructureLetter extends AbstractAppendOnlyEntity {

    @Column(name = "number", nullable = false, updatable = false)
    private String number;

    @Column(name = "verification_code", nullable = false, updatable = false)
    private String verificationCode;

    @Column(name = "restructure_id", nullable = false, updatable = false)
    private UUID restructureId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    /** The offer it amends; null for purchases opened before offers existed. */
    @Column(name = "offer_letter_id", updatable = false)
    private UUID offerLetterId;

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

    @Column(name = "opening", nullable = false, updatable = false, columnDefinition = "text")
    private String opening;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "clauses", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] clauses;

    @Column(name = "closing", nullable = false, updatable = false, columnDefinition = "text")
    private String closing;

    @Column(name = "sign_off", nullable = false, updatable = false)
    private String signOff;
}
