package com.techcomfort.landvaultbackend.tenancy.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code reviewerName} resolves {@code VerificationDecision.reviewerUserId}
 * via {@code IdentityApi} — the entity stores only the id. {@code decision}
 * is the enum's wire value — see AGENTS.md. {@code failedDocumentIds} is
 * always {@code null}: {@code verification_decision_documents} (the join
 * table backing it) has no entity/repository yet in this read-only slice —
 * out of scope, see AGENTS.md, not fabricated as an empty list either since
 * "no data available" and "genuinely zero failed documents" are different
 * facts this slice can't yet tell apart.
 */
public record VerificationDecisionDto(
        UUID id,
        String reviewerName,
        Instant timestamp,
        String decision,
        String reason,
        List<UUID> failedDocumentIds
) {
}
