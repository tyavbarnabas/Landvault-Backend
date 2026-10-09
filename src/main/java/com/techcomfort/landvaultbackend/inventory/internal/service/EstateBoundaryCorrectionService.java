package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonWriter;
import com.techcomfort.landvaultbackend.conflicts.ConflictChanges;
import com.techcomfort.landvaultbackend.conflicts.ConflictDetectionApi;
import com.techcomfort.landvaultbackend.conflicts.ConflictItem;
import com.techcomfort.landvaultbackend.conflicts.ConflictPublicationCheck;
import com.techcomfort.landvaultbackend.inventory.dto.BoundaryChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.CorrectEstateBoundaryRequest;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateBoundaryChange;
import com.techcomfort.landvaultbackend.inventory.internal.enums.BoundaryChangeStatus;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateBoundaryChangeRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Polygon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Correcting an estate's boundary (FP-2, option C, decided with the user).
 * <ul>
 *   <li>Same checks as adding one: inside the declared state, every mapped
 *       plot still inside.</li>
 *   <li>Applied at once when the estate isn't published, or when the land
 *       changes by no more than the review threshold (default 5%, measured as
 *       land added plus land removed). A published estate changing more
 *       waits for a Super Admin, and its current boundary stays live.</li>
 *   <li>Applying re-runs overlap detection: a same-company overlap that
 *       clears closes itself; a cross-company one that clears is held for a
 *       human (CD-9's rule), and a new cross-company overlap pulls the
 *       estate off the marketplace.</li>
 *   <li>Every correction is a history row; nothing is overwritten.</li>
 * </ul>
 * See AGENTS.md, "Correcting an estate boundary".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstateBoundaryCorrectionService {

    private final EstateRepository estateRepository;
    private final EstateBoundaryChangeRepository changes;
    private final GeoJsonPolygonParser geoJsonParser;
    private final GeometryCalculator geometry;
    private final StateBoundaryService stateBoundaries;
    private final ConflictDetectionApi conflictDetection;
    private final TenancyApi tenancyApi;
    private final AuditApi auditApi;

    @Value("${landvault.estates.boundary-correction.review-threshold-pct:5}")
    private BigDecimal reviewThresholdPct;

    // --- the developer ---

    @Transactional
    public BoundaryChangeDto correct(UUID estateId, CorrectEstateBoundaryRequest request) {
        TenantScope scope = currentScope();
        Estate estate = ownedWritableEstate(estateId, scope);
        if (estate.getFootprint() == null) {
            throw new InventoryException.StateConflict("BOUNDARY_NOT_SET",
                    "This estate has no boundary yet. Add one with POST .../boundary.");
        }
        if (changes.existsByEstateIdAndStatus(estateId, BoundaryChangeStatus.PENDING)) {
            throw new InventoryException.StateConflict("BOUNDARY_CHANGE_PENDING",
                    "A boundary correction for this estate is waiting for review. Withdraw it first, or wait for the decision.");
        }
        Polygon proposed = geoJsonParser.parse(request.footprint(), "footprint");
        if (proposed == null) {
            throw new InventoryException.InvalidGeometry("footprint", "A boundary is required.");
        }
        requireFits(estate, proposed);

        BigDecimal previousArea = geometry.areaInSquareMetres(estate.getFootprint());
        BigDecimal changed = geometry.changedArea(estate.getFootprint(), proposed);
        if (changed.signum() == 0) {
            throw new InventoryException.ImmutableField("BOUNDARY_UNCHANGED", "That is the boundary the estate already has.");
        }
        BigDecimal pct = changed.multiply(BigDecimal.valueOf(100)).divide(previousArea, 2, RoundingMode.HALF_UP);
        boolean needsReview = Boolean.TRUE.equals(estate.getPublished()) && pct.compareTo(reviewThresholdPct) > 0;

        EstateBoundaryChange change = EstateBoundaryChange.builder()
                .estateId(estateId)
                .previousFootprint(estate.getFootprint())
                .proposedFootprint(proposed)
                .previousAreaSqm(previousArea)
                .proposedAreaSqm(geometry.areaInSquareMetres(proposed))
                .changedAreaSqm(changed)
                .changedPct(pct)
                .reason(request.reason().trim())
                .status(needsReview ? BoundaryChangeStatus.PENDING : BoundaryChangeStatus.APPLIED)
                .requestedBy(scope.userId())
                .build();
        change.setTenantId(estate.getTenantId());
        change.setBranchId(estate.getBranchId());
        change = saveFlushingRace(change);

        if (needsReview) {
            auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.boundary_change_requested", "estate", estateId,
                    estate.getTenantId(), "Boundary correction to published estate '" + estate.getName() + "' changes "
                            + pct + "% of its land (" + previousArea + " → " + change.getProposedAreaSqm()
                            + " sqm); waiting for review. Reason: " + change.getReason()));
            return toDto(change, estate.getName(), null, null);
        }
        return toDto(change, estate.getName(), null, apply(estate, change, scope.userId(), "estate.boundary_corrected"));
    }

    @Transactional(readOnly = true)
    public List<BoundaryChangeDto> history(UUID estateId) {
        TenantScope scope = currentScope();
        Estate estate = estateRepository.findByIdAndTenantId(estateId, requireTenant(scope))
                .orElseThrow(InventoryException.EstateNotFound::new);
        return changes.findByEstateIdOrderByCreatedAtDesc(estateId).stream()
                .map(c -> toDto(c, estate.getName(), null, null))
                .toList();
    }

    @Transactional
    public BoundaryChangeDto withdraw(UUID estateId, UUID changeId) {
        TenantScope scope = currentScope();
        Estate estate = ownedWritableEstate(estateId, scope);
        EstateBoundaryChange change = changes.findForUpdate(changeId)
                .filter(c -> c.getEstateId().equals(estateId))
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound("Boundary change not found."));
        requirePending(change);
        change.setStatus(BoundaryChangeStatus.WITHDRAWN);
        changes.saveAndFlush(change);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.boundary_change_withdrawn", "estate", estateId,
                estate.getTenantId(), "Pending boundary correction to '" + estate.getName() + "' withdrawn."));
        return toDto(change, estate.getName(), null, null);
    }

    // --- the reviewer (platform scope) ---

    @Transactional(readOnly = true)
    public List<BoundaryChangeDto> listForReview(BoundaryChangeStatus status) {
        List<EstateBoundaryChange> rows = changes.findByStatusOrderByCreatedAtAsc(status);
        Map<UUID, Estate> estates = estateRepository.findAllById(rows.stream().map(EstateBoundaryChange::getEstateId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Estate::getId, Function.identity()));
        Map<UUID, String> companies = tenancyApi.organizationNamesFor(
                new HashSet<>(rows.stream().map(EstateBoundaryChange::getTenantId).toList()));
        return rows.stream()
                .map(c -> toDto(c, estates.containsKey(c.getEstateId()) ? estates.get(c.getEstateId()).getName() : null,
                        companies.get(c.getTenantId()), null))
                .toList();
    }

    /**
     * Re-checks everything, because the estate may have changed while the
     * request waited (a plot added near the edge), then applies it exactly as
     * a small correction would be.
     */
    @Transactional
    public BoundaryChangeDto approve(UUID changeId, String note) {
        TenantScope scope = currentScope();
        EstateBoundaryChange change = changes.findForUpdate(changeId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound("Boundary change not found."));
        requirePending(change);
        Estate estate = estateRepository.findById(change.getEstateId()).orElseThrow(InventoryException.EstateNotFound::new);
        requireFits(estate, change.getProposedFootprint());

        change.setStatus(BoundaryChangeStatus.APPROVED);
        change.setDecidedBy(scope.userId());
        change.setDecidedAt(Instant.now());
        change.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        changes.saveAndFlush(change);
        return toDto(change, estate.getName(), tenancyApi.organizationNamesFor(List.of(estate.getTenantId()))
                .get(estate.getTenantId()), apply(estate, change, scope.userId(), "estate.boundary_change_approved"));
    }

    @Transactional
    public BoundaryChangeDto reject(UUID changeId, String note) {
        TenantScope scope = currentScope();
        if (note == null || note.isBlank()) {
            throw new InventoryException.InvalidRequest("A note is required to reject a boundary correction; the developer sees it.");
        }
        EstateBoundaryChange change = changes.findForUpdate(changeId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound("Boundary change not found."));
        requirePending(change);
        Estate estate = estateRepository.findById(change.getEstateId()).orElseThrow(InventoryException.EstateNotFound::new);
        change.setStatus(BoundaryChangeStatus.REJECTED);
        change.setDecidedBy(scope.userId());
        change.setDecidedAt(Instant.now());
        change.setDecisionNote(note.trim());
        changes.saveAndFlush(change);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.boundary_change_rejected", "estate", estate.getId(),
                estate.getTenantId(), "Boundary correction to '" + estate.getName() + "' rejected: " + note.trim()
                        + ". The previous boundary stays."));
        return toDto(change, estate.getName(), tenancyApi.organizationNamesFor(List.of(estate.getTenantId()))
                .get(estate.getTenantId()), null);
    }

    // --- shared ---

    private record Applied(ConflictPublicationCheck check, ConflictChanges changes) {
    }

    /** Swap the boundary in, re-run overlap detection, and record it — with what it raised and cleared. */
    private Applied apply(Estate estate, EstateBoundaryChange change, UUID actor, String action) {
        List<ConflictItem> before = conflictDetection.conflictsOf(estate.getId(), estate.getTenantId());
        estate.setFootprint(change.getProposedFootprint());
        // Through the repository so a constraint violation is translated, not a 500 — see AGENTS.md.
        estateRepository.saveAndFlush(estate);
        int conflicts = conflictDetection.detectForEstateBoundary(estate.getId());
        ConflictPublicationCheck check = conflictDetection.publicationCheckFor(estate.getId());
        auditApi.record(AuditEntryRequest.of(actor, action, "estate", estate.getId(), estate.getTenantId(),
                "Boundary of '" + estate.getName() + "' now " + change.getProposedAreaSqm() + " sqm (was "
                        + change.getPreviousAreaSqm() + "; " + change.getChangedPct() + "% of the land changed); "
                        + conflicts + " live conflict(s) after detection. Reason: " + change.getReason()));
        log.info("Boundary of estate {} replaced ({}% changed) by {}", estate.getId(), change.getChangedPct(), actor);
        return new Applied(check, ConflictChanges.between(before, conflictDetection.conflictsOf(estate.getId(), estate.getTenantId())));
    }

    private void requireFits(Estate estate, Polygon boundary) {
        stateBoundaries.requireWithinDeclaredState(boundary, estate);
        List<String> outside = geometry.plotsOutside(estate.getId(), boundary);
        if (!outside.isEmpty()) {
            throw new InventoryException.PlotOutsideEstate("These plots would fall outside the corrected boundary: "
                    + String.join("; ", outside) + ". Correct those plots first, or draw the boundary to include them.");
        }
    }

    private static void requirePending(EstateBoundaryChange change) {
        if (change.getStatus() != BoundaryChangeStatus.PENDING) {
            throw new InventoryException.StateConflict("BOUNDARY_CHANGE_NOT_PENDING",
                    "That boundary correction isn't waiting for review (it is " + change.getStatus().wire() + ").");
        }
    }

    private EstateBoundaryChange saveFlushingRace(EstateBoundaryChange change) {
        try {
            return changes.saveAndFlush(change);
        } catch (DataIntegrityViolationException e) {
            throw new InventoryException.StateConflict("BOUNDARY_CHANGE_PENDING",
                    "A boundary correction for this estate is waiting for review.");
        }
    }

    private Estate ownedWritableEstate(UUID estateId, TenantScope scope) {
        Estate estate = estateRepository.findByIdAndTenantId(estateId, requireTenant(scope))
                .orElseThrow(InventoryException.EstateNotFound::new);
        EstateWriteAccess.requireWritable(estate);
        return estate;
    }

    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new InventoryException.EstateNotFound();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    private static BoundaryChangeDto toDto(EstateBoundaryChange c, String estateName, String companyName,
                                           Applied applied) {
        ConflictPublicationCheck check = applied == null ? null : applied.check();
        return new BoundaryChangeDto(c.getId(), c.getEstateId(), estateName, companyName, c.getStatus().wire(),
                c.getPreviousAreaSqm(), c.getProposedAreaSqm(), c.getChangedAreaSqm(), c.getChangedPct(),
                c.getReason(), c.getRequestedBy(), c.getCreatedAt(), c.getDecidedBy(), c.getDecidedAt(),
                c.getDecisionNote(), GeoJsonPolygonWriter.toGeoJson(c.getPreviousFootprint()),
                GeoJsonPolygonWriter.toGeoJson(c.getProposedFootprint()),
                check == null ? null : check.blocked(), check == null ? null : check.blockReason(),
                check == null ? null : check.warningConflictCount(), applied == null ? null : applied.changes());
    }
}
