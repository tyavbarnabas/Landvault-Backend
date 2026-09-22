package com.techcomfort.landvaultbackend.conflicts.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/admin/listing-conflicts/{id}/status} (CD-7).
 * <p>
 * {@code reason} is optional here and required by the service only for a
 * <em>decision</em> ({@code confirmed_duplicate}/{@code dismissed}) —
 * moving to {@code investigating} is just picking the work up, and
 * demanding a justification for that would train reviewers to type
 * something meaningless.
 * <p>
 * There is deliberately no reviewer field: the decider is the authenticated
 * caller, resolved from {@code TenantContext}, never the request body —
 * same rule as verification decisions (AGENTS.md). A client-supplied
 * reviewer would let any caller attribute a decision to someone else.
 */
public record ConflictStatusRequest(
        @NotBlank String status,
        String reason
) {
}
