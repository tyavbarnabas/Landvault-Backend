package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * What happens if a buyer falls behind or fails a condition. Versioned; the
 * current policy is the highest version. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_default_terms")
@SQLRestriction("deleted = false")
public class EstateDefaultTerms extends AbstractEntity {

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    @Column(name = "revocation_trigger", nullable = false, columnDefinition = "text")
    private String revocationTrigger;

    @Column(name = "revocation_notice_days")
    private Integer revocationNoticeDays;

    /**
     * Required, because both source letters are silent on it — and what
     * happens to money already paid is the single most consequential thing
     * either of them leaves out.
     */
    @Column(name = "on_revocation_refund", nullable = false, columnDefinition = "text")
    private String onRevocationRefund;

    /**
     * Declared data only (DF-4). Nothing tracks or surfaces it: that needs
     * an attention surface and a second scheduled job, which belong in their
     * own slice. Double King clause 2 gives three months.
     */
    @Column(name = "development_deadline_months")
    private Integer developmentDeadlineMonths;

    /**
     * Disclosed at purchase, not enforced — there is no resale module.
     * <p>
     * TODO: when resale ships, this gates listing. Note the frontend already
     * models developer consent at <em>transfer</em> time, which is this
     * epic's own failure one layer up: a real restriction arriving after the
     * seller has listed, negotiated and accepted an offer.
     */
    @Column(name = "transfer_requires_consent", nullable = false)
    private Boolean transferRequiresConsent;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;
}
