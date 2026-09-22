package com.techcomfort.landvaultbackend.conflicts.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.conflicts.dto.ConflictStatusRequest;
import com.techcomfort.landvaultbackend.conflicts.internal.domain.ListingConflict;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictStatus;
import com.techcomfort.landvaultbackend.conflicts.internal.exceptions.ConflictException;
import com.techcomfort.landvaultbackend.conflicts.internal.repository.ListingConflictRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Working a conflict through to a decision (CD-7).
 * <p>
 * Platform-scope only, so this one <em>can</em> use the repository — the
 * caller is a Super Admin whose scope satisfies changeset 046's policy.
 * Detection cannot, which is why it goes through definer functions instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictReviewService {

    private final ListingConflictRepository repository;
    private final AuditApi auditApi;

    /**
     * Moves a conflict along. The decider is the authenticated caller,
     * never the request body — same "never client-supplied" rule as
     * verification decisions (AGENTS.md).
     */
    @Transactional
    public ListingConflict changeStatus(UUID conflictId, ConflictStatusRequest request) {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));

        ListingConflict conflict = repository.findById(conflictId)
                .orElseThrow(ConflictException.ConflictNotFound::new);

        ConflictStatus target = ConflictStatus.fromValue(request.status());
        ConflictStatus current = conflict.getStatus();

        requireTransitionAllowed(current, target);
        requireReasonForDecision(target, request.reason());

        conflict.setStatus(target);
        conflict.setResolutionReason(request.reason());
        conflict.setResolvedByUserId(scope.userId());
        // INVESTIGATING is picking the work up, not finishing it — stamping
        // a resolved time there would make an in-progress conflict look
        // closed in any report that reads the column.
        conflict.setResolvedAt(target == ConflictStatus.INVESTIGATING ? null : Instant.now());
        conflict = repository.save(conflict);

        recordAudit(conflict, target, request.reason(), scope.userId());
        log.info("Listing conflict {} moved {} -> {} by actor {}",
                conflictId, current.getValue(), target.getValue(), scope.userId());
        return conflict;
    }

    /**
     * {@code AUTO_RESOLVED} is reachable by detection only — it is a
     * statement that the geometry no longer overlaps, which is a fact about
     * the world rather than a judgement a reviewer is entitled to assert.
     * Letting it be set by hand would mean a conflict could be marked
     * "fixed itself" while the land still overlaps.
     * <p>
     * A decision is terminal, <strong>with one deliberate exception</strong>:
     * {@code CONFIRMED_DUPLICATE → DISMISSED}. Geometry alone is never
     * allowed to lift a confirmed duplicate — that is the entire point of
     * {@code geometryClearedAt} existing (see the entity and AGENTS.md) —
     * but a human still needs a way to stand down once they have actually
     * reviewed a correction and agree it resolves the dispute. Dismissing
     * is that decision, made the same way any other decision is: with a
     * reason, audited, and never automatic. No other exit from
     * {@code CONFIRMED_DUPLICATE} is allowed — not back to
     * {@code INVESTIGATING}, and not re-confirming — because there is
     * nothing left to investigate once a human has already decided; the
     * only question left is whether that decision still stands.
     * <p>
     * Every other decision is fully terminal. Re-opening a dismissed
     * conflict is not a status flip: if the geometry still overlaps,
     * detection raises a fresh conflict for the pair (the unique index
     * deliberately ignores {@code DISMISSED}/{@code AUTO_RESOLVED} rows),
     * which leaves the original decision intact as history rather than
     * overwriting it.
     */
    private static void requireTransitionAllowed(ConflictStatus current, ConflictStatus target) {
        if (target == ConflictStatus.AUTO_RESOLVED) {
            throw new ConflictException.InvalidTransition(
                    "auto_resolved is set by the system when geometry stops overlapping, and cannot be "
                            + "applied manually. Use dismissed if this is a false positive.");
        }
        if (target == ConflictStatus.OPEN) {
            throw new ConflictException.InvalidTransition(
                    "A conflict cannot be moved back to open. It starts there when detected.");
        }
        if (current == target) {
            throw new ConflictException.InvalidTransition(
                    "This conflict is already " + target.getValue() + ". This usually means you are acting "
                            + "on a stale view — reload before deciding.");
        }
        if (!current.isLive()) {
            throw new ConflictException.InvalidTransition(
                    "This conflict is already closed as " + current.getValue() + " and cannot be changed. "
                            + "If the boundaries still overlap, detection will raise a new conflict.");
        }
        if (current == ConflictStatus.CONFIRMED_DUPLICATE && target != ConflictStatus.DISMISSED) {
            throw new ConflictException.InvalidTransition(
                    "A confirmed duplicate can only be moved to dismissed, and only as a deliberate "
                            + "decision that a reviewed correction resolves it. It cannot be re-confirmed "
                            + "or sent back to investigating.");
        }
    }

    /**
     * A decision has consequences — it blocks or unblocks a company's
     * listing — so it carries a recorded justification. Moving to
     * {@code investigating} deliberately does not: it only means somebody
     * picked the work up, and demanding a justification for that trains
     * reviewers to type something meaningless.
     */
    private static void requireReasonForDecision(ConflictStatus target, String reason) {
        boolean decision = target == ConflictStatus.CONFIRMED_DUPLICATE || target == ConflictStatus.DISMISSED;
        if (decision && (reason == null || reason.isBlank())) {
            throw new ConflictException.InvalidTransition(
                    "A reason is required when recording " + target.getValue()
                            + " — the resolution has to be auditable.");
        }
    }

    /**
     * One entry per company involved. A conflict spans two of them, and
     * both need it on their own record — but only when they are genuinely
     * different companies, or a same-tenant conflict would double-log one
     * company's record. Matches the frontend's own mock reasoning
     * ({@code listingConflictsService.reviewConflict}).
     */
    private void recordAudit(ListingConflict conflict, ConflictStatus target, String reason, UUID actorUserId) {
        String action = "listing_conflict." + target.name().toLowerCase();
        String detail = "Listing conflict " + conflict.getId() + " (" + conflict.getConflictType().getValue()
                + ", " + conflict.getSeverity().getValue() + ", "
                + conflict.getOverlapAreaSqm() + " sqm overlap) marked " + target.getValue()
                + (reason == null || reason.isBlank() ? "." : " — " + reason);

        Set<UUID> tenants = conflict.isCrossTenant()
                ? Set.of(conflict.getLeftTenantId(), conflict.getRightTenantId())
                : Set.of(conflict.getLeftTenantId());
        for (UUID tenantId : tenants) {
            auditApi.record(AuditEntryRequest.of(
                    actorUserId, action, "listing_conflict", conflict.getId(), tenantId, detail));
        }
    }
}
