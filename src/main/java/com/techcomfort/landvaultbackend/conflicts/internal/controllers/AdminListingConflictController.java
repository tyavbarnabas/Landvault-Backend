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
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('admin.marketplace.conflicts')")
    public ResponseEntity<ListingConflictDto> changeStatus(
            @PathVariable UUID id, @Valid @RequestBody ConflictStatusRequest request) {
        reviewService.changeStatus(id, request);
        return ResponseEntity.ok(queryService.getForAdmin(id));
    }
}
