package com.techcomfort.landvaultbackend.conflicts.internal.service;

import com.techcomfort.landvaultbackend.audit.ActorNameResolver;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.conflicts.EstateLabelResolver;
import com.techcomfort.landvaultbackend.conflicts.dto.ListingConflictDto;
import com.techcomfort.landvaultbackend.conflicts.dto.TenantConflictDto;
import com.techcomfort.landvaultbackend.conflicts.internal.domain.ListingConflict;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictSeverity;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictStatus;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictType;
import com.techcomfort.landvaultbackend.conflicts.internal.exceptions.ConflictException;
import com.techcomfort.landvaultbackend.conflicts.internal.repository.ListingConflictRepository;
import com.techcomfort.landvaultbackend.conflicts.internal.repository.ListingConflictSpecifications;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The two read surfaces, which differ in what they are allowed to say.
 * <ul>
 *   <li>{@link #searchForAdmin} — platform scope, names both sides.</li>
 *   <li>{@link #forTenantEstate} — a tenant's own view, which
 *       <strong>never</strong> learns who the other party is (CD-11).</li>
 * </ul>
 * They are deliberately separate methods returning separate types rather
 * than one method with a flag: a flag is something a later caller can pass
 * wrongly.
 */
@Service
@RequiredArgsConstructor
public class ConflictQueryService {

    private static final String UNKNOWN_ESTATE = "Unknown estate";
    private static final String UNKNOWN_PLOT = "Unknown plot";
    private static final String UNKNOWN_COMPANY = "Unknown company";

    private final ListingConflictRepository repository;
    private final EstateLabelResolver estateLabelResolver;
    private final ActorNameResolver actorNameResolver;
    private final TenancyApi tenancyApi;

    @PersistenceContext
    private EntityManager entityManager;

    // --- admin queue (CD-6) ---

    @Transactional(readOnly = true)
    public PageResponse<ListingConflictDto> searchForAdmin(
            List<ConflictSeverity> severities, List<ConflictStatus> statuses,
            ConflictType conflictType, Pageable pageable) {

        Page<ListingConflict> page = repository.findAll(
                ListingConflictSpecifications.matching(severities, statuses, conflictType), pageable);

        // One resolver call per kind for the whole page, never one per row.
        Set<UUID> estateIds = new HashSet<>();
        Set<UUID> plotIds = new HashSet<>();
        Set<UUID> tenantIds = new HashSet<>();
        Set<UUID> reviewerIds = new HashSet<>();
        for (ListingConflict conflict : page.getContent()) {
            if (conflict.getConflictType() == ConflictType.ESTATE_OVERLAP) {
                estateIds.add(conflict.getLeftEntityId());
                estateIds.add(conflict.getRightEntityId());
            } else {
                plotIds.add(conflict.getLeftEntityId());
                plotIds.add(conflict.getRightEntityId());
            }
            tenantIds.add(conflict.getLeftTenantId());
            tenantIds.add(conflict.getRightTenantId());
            if (conflict.getResolvedByUserId() != null) {
                reviewerIds.add(conflict.getResolvedByUserId());
            }
        }

        Map<UUID, String> estateNames = estateLabelResolver.estateNamesFor(estateIds);
        Map<UUID, String> plotLabels = estateLabelResolver.plotLabelsFor(plotIds);
        Map<UUID, String> companyNames = tenancyApi.organizationNamesFor(tenantIds);
        Map<UUID, String> reviewerNames = actorNameResolver.displayNamesFor(reviewerIds);

        return PageResponses.from(page, conflict ->
                toAdminDto(conflict, estateNames, plotLabels, companyNames, reviewerNames));
    }

    @Transactional(readOnly = true)
    public ListingConflictDto getForAdmin(UUID conflictId) {
        ListingConflict conflict = repository.findById(conflictId)
                .orElseThrow(ConflictException.ConflictNotFound::new);
        boolean estateLevel = conflict.getConflictType() == ConflictType.ESTATE_OVERLAP;
        // Entity ids can never collide — the pair-ordering CHECK constraint
        // guarantees left < right — but tenant ids genuinely can: a
        // same-tenant (MEDIUM) conflict has leftTenantId == rightTenantId.
        // Set.of(a, b) throws IllegalArgumentException("duplicate element")
        // on a repeated argument, so it's only safe for the entity ids.
        // Found by a real request, not by inspection: the first admin GET
        // on a same-tenant conflict returned a 400 from this line.
        Set<UUID> entityIds = Set.of(conflict.getLeftEntityId(), conflict.getRightEntityId());
        Set<UUID> tenantIds = new HashSet<>(List.of(conflict.getLeftTenantId(), conflict.getRightTenantId()));

        return toAdminDto(
                conflict,
                estateLevel ? estateLabelResolver.estateNamesFor(entityIds) : Map.of(),
                estateLevel ? Map.of() : estateLabelResolver.plotLabelsFor(entityIds),
                tenancyApi.organizationNamesFor(tenantIds),
                conflict.getResolvedByUserId() == null
                        ? Map.of()
                        : actorNameResolver.displayNamesFor(Set.of(conflict.getResolvedByUserId())));
    }

    private static ListingConflictDto toAdminDto(
            ListingConflict conflict, Map<UUID, String> estateNames, Map<UUID, String> plotLabels,
            Map<UUID, String> companyNames, Map<UUID, String> reviewerNames) {

        boolean estateLevel = conflict.getConflictType() == ConflictType.ESTATE_OVERLAP;
        Map<UUID, String> labels = estateLevel ? estateNames : plotLabels;
        String fallback = estateLevel ? UNKNOWN_ESTATE : UNKNOWN_PLOT;

        return new ListingConflictDto(
                conflict.getId(),
                conflict.getConflictType().getValue(),
                conflict.getLeftEntityId(),
                labels.getOrDefault(conflict.getLeftEntityId(), fallback),
                conflict.getLeftTenantId(),
                companyNames.getOrDefault(conflict.getLeftTenantId(), UNKNOWN_COMPANY),
                conflict.getRightEntityId(),
                labels.getOrDefault(conflict.getRightEntityId(), fallback),
                conflict.getRightTenantId(),
                companyNames.getOrDefault(conflict.getRightTenantId(), UNKNOWN_COMPANY),
                conflict.getEstateId(),
                conflict.isCrossTenant(),
                conflict.getSeverity().getValue(),
                conflict.getOverlapAreaSqm(),
                conflict.getLeftOverlapPct(),
                conflict.getRightOverlapPct(),
                conflict.getDetectedAt(),
                conflict.getStatus().getValue(),
                conflict.getGeometryClearedAt(),
                conflict.getResolvedByUserId() == null
                        ? null
                        : reviewerNames.getOrDefault(conflict.getResolvedByUserId(), "Unknown user"),
                conflict.getResolvedAt(),
                conflict.getResolutionReason());
    }

    // --- tenant view (CD-11) ---

    /**
     * The owning tenant's view of conflicts on one of their estates.
     * <p>
     * Reads through {@code landvault_tenant_estate_conflicts()}, not the
     * repository, for two reasons that reinforce each other:
     * {@code listing_conflicts} is platform-scope only so a repository read
     * would return nothing here, and the function selects only this
     * tenant's own side — so the counterparty's identity never leaves the
     * database, rather than being fetched and then dropped during mapping.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<TenantConflictDto> forTenantEstate(UUID estateId, UUID tenantId) {
        List<Object[]> rows = entityManager
                .createNativeQuery("""
                        SELECT conflict_id, conflict_type, own_entity_id, overlap_area_sqm,
                               own_overlap_pct, severity, status, geometry_cleared_at, detected_at
                        FROM landvault_tenant_estate_conflicts(:estateId, :tenantId)
                        """)
                .setParameter("estateId", estateId)
                .setParameter("tenantId", tenantId)
                .getResultList();

        Set<UUID> estateIds = new HashSet<>();
        Set<UUID> plotIds = new HashSet<>();
        for (Object[] row : rows) {
            UUID entityId = (UUID) row[2];
            if (ConflictType.ESTATE_OVERLAP.name().equals(row[1])) {
                estateIds.add(entityId);
            } else {
                plotIds.add(entityId);
            }
        }
        // Safe to resolve: these are the caller's OWN estate and plots, which
        // they can already read. No counterparty id exists in `rows` to
        // accidentally look up.
        Map<UUID, String> estateNames = estateLabelResolver.estateNamesFor(estateIds);
        Map<UUID, String> plotLabels = estateLabelResolver.plotLabelsFor(plotIds);

        // Live conflicts before closed records, so what's blocking the
        // listing is what the tenant reads first. Stable sort, so within each
        // group the function's own severity-then-area order is kept. Done
        // here rather than in the function's ORDER BY because changeset 047
        // is applied to a real database and no longer edited in place.
        // Caught live: a dismissed record was listed above the open conflict
        // actually pausing publication, because the two tied on severity
        // and area.
        return rows.stream()
                .map(row -> toTenantDto(row, estateNames, plotLabels))
                .sorted(java.util.Comparator.comparing(
                        (TenantConflictDto dto) -> !ConflictStatus.fromValue(dto.status()).isLive()))
                .toList();
    }

    private static TenantConflictDto toTenantDto(
            Object[] row, Map<UUID, String> estateNames, Map<UUID, String> plotLabels) {

        ConflictType type = ConflictType.valueOf((String) row[1]);
        ConflictSeverity severity = ConflictSeverity.valueOf((String) row[5]);
        ConflictStatus status = ConflictStatus.valueOf((String) row[6]);
        UUID entityId = (UUID) row[2];
        // Hibernate 6 materialises timestamptz as Instant, not
        // java.sql.Timestamp — casting to the latter threw a
        // ClassCastException that only showed up over real HTTP.
        Instant geometryClearedAt = (Instant) row[7];

        boolean blocks = status.isLive()
                && (severity == ConflictSeverity.HIGH || status == ConflictStatus.CONFIRMED_DUPLICATE);
        boolean underReview = status.isLive() && geometryClearedAt != null;

        return new TenantConflictDto(
                (UUID) row[0],
                type.getValue(),
                entityId,
                type == ConflictType.ESTATE_OVERLAP
                        ? estateNames.getOrDefault(entityId, UNKNOWN_ESTATE)
                        : plotLabels.getOrDefault(entityId, UNKNOWN_PLOT),
                (BigDecimal) row[3],
                (BigDecimal) row[4],
                severity.getValue(),
                status.getValue(),
                blocks,
                underReview,
                guidanceFor(type, severity, status, blocks, underReview, geometryClearedAt != null),
                (Instant) row[8]);
    }

    /**
     * Plain-language, factual, and never an accusation — most conflicts are
     * survey errors, and the copy says so. Same discipline already applied
     * to arrears messaging (AGENTS.md).
     * <p>
     * <strong>Never claims a correction clears a blocking conflict
     * automatically</strong> — it doesn't, precisely so a boundary can't be
     * nudged just under the sliver threshold to dodge review while keeping
     * the disputed ground. Only the genuinely automatic cases (a MEDIUM
     * conflict that was never confirmed, or an ordinary plot conflict) say
     * "clears automatically", because for those it's true.
     */
    private static String guidanceFor(
            ConflictType type, ConflictSeverity severity, ConflictStatus status,
            boolean blocks, boolean underReview, boolean geometryCleared) {

        if (!status.isLive()) {
            if (status == ConflictStatus.AUTO_RESOLVED) {
                return "This was resolved automatically once the boundary no longer overlapped another "
                        + "listing. Kept here only as a record — no action is needed.";
            }
            // DISMISSED. Deliberately never says "false positive": since
            // CONFIRMED_DUPLICATE -> DISMISSED exists, a dismissal can mean
            // "a correction was reviewed and accepted" just as easily as
            // "this was never a real problem" — and a verdict on which is
            // not something the tenant's copy needs to assert. Caught live:
            // a confirmed duplicate dismissed after correction was being
            // described to its owner as a false positive.
            return geometryCleared
                    ? "This was closed after the boundary was corrected and our team reviewed the update. "
                    + "Kept here only as a record — no action is needed."
                    : "Our team reviewed this and closed it. Kept here only as a record — no action is "
                    + "needed.";
        }
        if (status == ConflictStatus.CONFIRMED_DUPLICATE) {
            return underReview
                    ? "This was confirmed as a genuine overlap with another listing. We noticed the "
                    + "boundary has since been updated — our team is reviewing the correction, and "
                    + "publication stays paused until that review is complete."
                    : "This was reviewed and confirmed as a genuine overlap with another listing. "
                    + "Publication stays paused. If you believe this was decided in error, or you've "
                    + "corrected the boundary, let us know and we'll take another look.";
        }
        if (type == ConflictType.PLOT_OVERLAP) {
            return "Two plot boundaries in this estate share some ground. This is usually a surveying or "
                    + "data-entry error. Correcting the plot coordinates clears this automatically — "
                    + "listing and sales are not affected in the meantime.";
        }
        if (severity == ConflictSeverity.MEDIUM) {
            return "Two of your own estate boundaries share some ground. This usually means one of the "
                    + "surveys needs adjusting. Correcting the coordinates clears this automatically, and "
                    + "publication is not affected.";
        }
        if (!blocks) {
            return "This estate's boundary shares ground with another listing on the platform. Our team "
                    + "has reviewed it and publication is not affected.";
        }
        return underReview
                ? "We noticed this estate's boundary was updated and no longer overlaps the other "
                + "listing. Our team is reviewing the update, and publication will resume once that "
                + "review is complete. No action is needed from you right now."
                : "This estate's boundary shares ground with another listing on the platform. Publication "
                + "is paused while our team reviews it. If your survey coordinates need adjusting, "
                + "updating them will be reviewed before publication can resume. We will be in touch if "
                + "we need anything from you.";
    }
}
