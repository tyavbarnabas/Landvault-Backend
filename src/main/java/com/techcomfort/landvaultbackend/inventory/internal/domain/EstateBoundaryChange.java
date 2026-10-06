package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.inventory.internal.enums.BoundaryChangeStatus;
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
import org.locationtech.jts.geom.Polygon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One correction to an estate's boundary, applied or reviewed (changeset
 * 069). Both shapes are kept — history, never an overwrite. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_boundary_changes")
@SQLRestriction("deleted = false")
public class EstateBoundaryChange extends AbstractEntity {

    @Column(name = "estate_id", nullable = false)
    private UUID estateId;

    @Column(name = "previous_footprint", nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon previousFootprint;

    @Column(name = "proposed_footprint", nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon proposedFootprint;

    @Column(name = "previous_area_sqm", nullable = false)
    private BigDecimal previousAreaSqm;

    @Column(name = "proposed_area_sqm", nullable = false)
    private BigDecimal proposedAreaSqm;

    @Column(name = "changed_area_sqm", nullable = false)
    private BigDecimal changedAreaSqm;

    @Column(name = "changed_pct", nullable = false)
    private BigDecimal changedPct;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BoundaryChangeStatus status;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;
}
