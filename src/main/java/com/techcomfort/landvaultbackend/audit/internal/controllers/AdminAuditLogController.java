package com.techcomfort.landvaultbackend.audit.internal.controllers;

import com.techcomfort.landvaultbackend.audit.dto.AuditLogEntryDto;
import com.techcomfort.landvaultbackend.audit.internal.service.AuditLogQueryService;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Reading the audit trail. <strong>There is no write, update, delete or
 * archive route here, and there must never be one</strong> — the whole point
 * of an append-only log is that nobody can quietly revise it. The entity
 * enforces this structurally ({@code AbstractAppendOnlyEntity} has no
 * {@code updatedAt} and no {@code deleted}); this surface must not
 * reintroduce what the schema deliberately omits.
 * <p>
 * Platform-scope only for now: a Super Admin sees every entry across all
 * tenants, via the existing platform-scope RLS bypass.
 * <p>
 * TODO: {@code audit_log_entries} carries no RLS policy, which is fine while
 * only platform staff can read it — but it becomes a real gap the moment
 * tenant staff need their own organization's trail through
 * {@code /api/portal/*}. That view needs both a policy on this table and a
 * decision about what a tenant may see: their own entries certainly, but
 * almost certainly NOT {@code privileged} support-access entries, which would
 * let a tenant watch the platform investigating them. See AGENTS.md.
 */
@RestController
@RequestMapping("/api/admin/audit-log")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_AUDIT)
public class AdminAuditLogController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogQueryService service;

    @Operation(
            summary = "Read the audit trail",
            description = """
                    Requires `admin.audit.view` (held by `super_admin` and `compliance_officer`, \
                    deliberately not by `platform_moderator`).

                    **Read-only by design. There is no write, update, delete or archive route here, \
                    and there must never be one** — the entries have no `updatedAt` and no soft-delete \
                    flag at the schema level either. An append-only log that can be quietly revised \
                    is not a record.

                    Newest first, always. `privileged` marks entries where a platform operator used \
                    support access to look into a tenant's data; filtering on it is the most likely \
                    reason to open this screen deliberately.

                    **Reads are not audited.** This log records actions, not page views — logging \
                    browsing would drown the entries that matter.

                    Platform scope only today: a Super Admin sees every tenant's entries. A \
                    tenant-facing view would need both a policy on the table and a decision about \
                    whether a tenant may see `privileged` entries about themselves.""")
    @ApiResponse(responseCode = "200", description = "A page of audit entries, newest first")
    @GetMapping
    @PreAuthorize("hasAuthority('admin.audit.view')")
    public ResponseEntity<PageResponse<AuditLogEntryDto>> list(
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean privileged,
            @RequestParam(required = false) Instant occurredAfter,
            @RequestParam(required = false) Instant occurredBefore,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {

        return ResponseEntity.ok(service.search(
                actorUserId, tenantId, targetType, targetId, action, privileged,
                occurredAfter, occurredBefore, pageable(cursor, limit)));
    }

    /**
     * Most recent first, always — an activity stream read oldest-first is
     * useless. An audit log grows without bound, so the page size is capped
     * rather than left to the caller.
     */
    private static Pageable pageable(String cursor, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Pageable base = PageResponses.pageable(cursor, size);
        return PageRequest.of(base.getPageNumber(), base.getPageSize(), Sort.by(Sort.Direction.DESC, "occurredAt"));
    }
}
