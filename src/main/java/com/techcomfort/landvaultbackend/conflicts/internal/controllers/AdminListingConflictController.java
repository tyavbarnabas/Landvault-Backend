package com.techcomfort.landvaultbackend.conflicts.internal.controllers;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.conflicts.dto.ConflictStatusRequest;
import com.techcomfort.landvaultbackend.conflicts.dto.ListingConflictDto;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictSeverity;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictStatus;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictType;
import com.techcomfort.landvaultbackend.conflicts.internal.service.ConflictQueryService;
import com.techcomfort.landvaultbackend.conflicts.internal.service.ConflictReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The Super Admin review queue (CD-6/CD-7).
 * <p>
 * Gated on {@code admin.marketplace.conflicts}, which was seeded back in
 * changeset 014 for exactly this feature (SA-3.4) and granted to
 * {@code super_admin} in 016 — verified present rather than re-created.
 * <p>
 * <strong>Platform scope only.</strong> Everything here names both
 * companies. The owning tenant's view is a different route returning a
 * different type that cannot carry a counterparty at all — see
 * {@code PortalEstateConflictController}.
 */
@RestController
@RequestMapping("/api/admin/listing-conflicts")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_CONFLICTS)
public class AdminListingConflictController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConflictQueryService queryService;
    private final ConflictReviewService reviewService;

    /**
     * Worst first — severity, then largest overlap — and that ordering is
     * fixed rather than caller-supplied: a queue whose whole purpose is
     * "look at genuine fraud risk first" should not be sortable into an
     * order that buries it.
     */
    @Operation(
            summary = "The conflict review queue",
            description = """
                    Requires `admin.marketplace.conflicts`.

                    Detected overlaps between two estates, or between two plots inside one estate. \
                    Unlike the tenant-facing view, this names **both** companies — reviewing a \
                    dispute is impossible otherwise.

                    **Ordering is fixed, not caller-supplied**: live conflicts before closed ones, \
                    then `high` before `medium`, then largest overlap first. A queue whose purpose \
                    is "look at genuine fraud risk first" should not be sortable into an order that \
                    buries it.

                    `high` means two different companies claim the same ground — one of them is \
                    wrong and a buyer could pay the wrong party. `medium` means one company's own \
                    boundaries overlap, which is almost always a survey error.""")
    @ApiResponse(responseCode = "200", description = "A page of conflicts, worst first")
    @GetMapping
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<PageResponse<ListingConflictDto>> list(
            @RequestParam(required = false) List<String> severity,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String conflictType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {

        Pageable pageable = PageResponses.pageable(
                cursor, limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE));

        return ResponseEntity.ok(queryService.searchForAdmin(
                severity == null ? null : severity.stream().map(ConflictSeverity::fromValue).toList(),
                status == null ? null : status.stream().map(ConflictStatus::fromValue).toList(),
                conflictType == null || conflictType.isBlank() ? null : ConflictType.fromValue(conflictType),
                pageable));
    }

    @Operation(summary = "One conflict in full",
            description = "Both entities, both companies, the overlap and each side's share, who "
                    + "decided what and why, and `geometryClearedAt` — set when the overlap has "
                    + "since disappeared but nobody has reviewed that yet.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The conflict"),
            @ApiResponse(responseCode = "404", description = "No such conflict", content = @Content())
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<ListingConflictDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(queryService.getForAdmin(id));
    }

    /**
     * Records a transition (CD-7). The decision is attributed to the
     * authenticated caller, and writes an audit entry against every company
     * involved.
     */
    @Operation(
            summary = "Record a decision on a conflict",
            description = """
                    `open` → `investigating` → `confirmed_duplicate` | `dismissed`. Requires \
                    `admin.marketplace.conflicts`.

                    **A decision needs a `reason`**; moving to `investigating` does not — that is \
                    picking the work up, and demanding a justification for it trains reviewers to \
                    type something meaningless.

                    **`auto_resolved` cannot be set by hand** (409). The system sets it when \
                    geometry stops overlapping; a reviewer asserting a conflict "resolved itself" \
                    while the land still overlaps would be claiming something they cannot know.

                    **A confirmed duplicate can only move to `dismissed`**, and only as a deliberate \
                    decision that a reviewed correction resolves it. Geometry changing never lifts \
                    it by itself: a boundary can be nudged just under the detection threshold while \
                    keeping nearly all of the disputed ground.

                    Every transition writes an audit entry against **each** company involved.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated conflict"),
            @ApiResponse(responseCode = "404", description = "No such conflict", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`INVALID_CONFLICT_TRANSITION`: not an "
                    + "allowed move, a decision with no reason, or a stale view",
                    content = @Content())
    })
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<ListingConflictDto> changeStatus(
            @PathVariable UUID id, @Valid @RequestBody ConflictStatusRequest request) {
        reviewService.changeStatus(id, request);
        return ResponseEntity.ok(queryService.getForAdmin(id));
    }
}
