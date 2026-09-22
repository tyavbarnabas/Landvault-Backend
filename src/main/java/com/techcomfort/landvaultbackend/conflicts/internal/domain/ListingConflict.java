package com.techcomfort.landvaultbackend.conflicts.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictSeverity;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictStatus;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictType;
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
 * A detected geometric overlap between two estates, or two plots in one
 * estate. Platform-owned — the inherited {@code tenantId}/{@code branchId}
 * stay null on every row, because a conflict belongs to neither company.
 * See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "listing_conflicts")
@SQLRestriction("deleted = false")
public class ListingConflict extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_type", nullable = false, length = 16)
    private ConflictType conflictType;

    /**
     * The lower of the two entity ids, always — the database enforces
     * {@code left_entity_id < right_entity_id} with a CHECK constraint.
     * Without that ordering, detecting A-vs-B and B-vs-A would create two
     * rows for one conflict and the unique index could not prevent it.
     */
    @Column(name = "left_entity_id", nullable = false)
    private UUID leftEntityId;

    @Column(name = "right_entity_id", nullable = false)
    private UUID rightEntityId;

    /** Both sides are stored: a conflict is not owned by one tenant. */
    @Column(name = "left_tenant_id", nullable = false)
    private UUID leftTenantId;

    @Column(name = "right_tenant_id", nullable = false)
    private UUID rightTenantId;

    /**
     * The estate both plots sit in, for a {@code PLOT_OVERLAP}; null for an
     * {@code ESTATE_OVERLAP}, where the two entities are themselves estates.
     */
    @Column(name = "estate_id")
    private UUID estateId;

    /** Square metres, via the geography cast — never square degrees. */
    @Column(name = "overlap_area_sqm", nullable = false, precision = 14, scale = 2)
    private BigDecimal overlapAreaSqm;

    @Column(name = "left_overlap_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal leftOverlapPct;

    @Column(name = "right_overlap_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal rightOverlapPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 8)
    private ConflictSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ConflictStatus status;

    @Column(name = "resolution_reason", columnDefinition = "text")
    private String resolutionReason;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Set by detection when a HIGH conflict's (or any confirmed duplicate's)
     * overlap drops away, without touching {@link #status}. Deliberately
     * does not by itself resolve anything — see AGENTS.md. Null again if
     * the pair overlaps once more on a later scan.
     */
    @Column(name = "geometry_cleared_at")
    private Instant geometryClearedAt;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** True when the two sides belong to different companies — what makes a conflict HIGH. */
    public boolean isCrossTenant() {
        return leftTenantId != null && !leftTenantId.equals(rightTenantId);
    }
}
