package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/admin/tenants/{id}/verification-decision} body.
 * Deliberately does NOT carry the frontend's {@code RecordDecisionInput.reviewerName}
 * field — that's a mock-mode-only convenience with no real session to derive
 * it from; the real reviewer identity is the authenticated caller's own
 * {@code AccessTokenClaims.userId()}, never client-supplied, same principle
 * as {@code tenant_id} never being client-supplied. See AGENTS.md.
 * <p>
 * {@code reason} is required (service-layer, not a DB/bean-validation
 * constraint — required for {@code REJECTED}/{@code REQUEST_MORE_INFO},
 * nullable for {@code APPROVED}) since the rule is behavioural, not a fixed
 * column property.
 */
public record VerificationDecisionRequest(
        @NotBlank String decision,
        String reason,
        List<UUID> failedDocumentIds
) {
}
