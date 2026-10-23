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

    /**
     * Phase 5b: revocable once an instalment is unpaid this many whole months
     * past its due date (HomeTrue minimum 3). Replaced the free-text trigger
     * and "on revocation" refund (changeset 107): a revoked buyer is refunded
     * as on withdrawal, and HomeTrue words the clause.
     */
    @Column(name = "revocation_after_months", nullable = false)
    private Integer revocationAfterMonths;

    /** As declared; the buyer is given at least HomeTrue's minimum (14) regardless. */
    @Column(name = "revocation_notice_days")
    private Integer revocationNoticeDays;

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

    /** Declared with the penalty tiers: whether penalties come back to a buyer who withdraws. */
    @Column(name = "penalties_refundable", nullable = false)
    private Boolean penaltiesRefundable;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;
}
