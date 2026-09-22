package com.techcomfort.landvaultbackend.conflicts.internal.controllers;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.conflicts.dto.TenantConflictDto;
import com.techcomfort.landvaultbackend.conflicts.internal.service.ConflictQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * What the affected tenant sees about conflicts on their own estate
 * (CD-11).
 * <p>
 * Returns {@link TenantConflictDto}, which has no field capable of holding
 * the other company's identity — and reads through a database function that
 * never selects those columns in the first place. Both defences are
 * deliberate; see the DTO and changeset 047.
 * <p>
 * Reuses {@code portal.estates.view} rather than inventing a new slug: this
 * is a read about an estate, by the same people who read the estate itself,
 * and a permission nothing distinguishes is a permission nobody manages
 * correctly.
 * <p>
 * A plain array, not a paginated envelope — one estate's conflicts are a
 * naturally small, bounded list, which AGENTS.md's pagination rule
 * explicitly allows. An estate with hundreds of conflicts would be a
 * detection bug, not a paging problem.
 */
@RestController
@RequestMapping("/api/portal/estates/{id}/conflicts")
@RequiredArgsConstructor
public class PortalEstateConflictController {

    private final ConflictQueryService queryService;

    /**
     * An estate that isn't the caller's returns an <strong>empty list</strong>,
     * not a 403 — the same non-disclosure reasoning as the 404 on the
     * estate routes themselves. A 403 would confirm that some other
     * company's estate exists at that id, which is precisely the kind of
     * fact this endpoint exists to withhold.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<List<TenantConflictDto>> list(@PathVariable UUID id) {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        if (scope.tenantId() == null) {
            // Platform staff have no tenant, so no "own estate" to ask
            // about. They use the admin queue, which is a different surface
            // with a different permission.
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(queryService.forTenantEstate(id, scope.tenantId()));
    }
}
