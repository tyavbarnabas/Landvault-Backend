package com.techcomfort.landvaultbackend.kyc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * {@code POST /api/admin/kyc/{userId}/decision}.
 * <p>
 * The reviewer's identity is <strong>never</strong> a field here: it comes
 * from the authenticated caller, the same rule as every other decision in
 * this system. A client-supplied reviewer would let any caller attribute a
 * decision to someone else.
 */
@Schema(
        name = "KycDecisionRequest",
        description = """
                Record a manual review decision on one buyer's verification.

                **A rejection must name the documents that failed.** Every other document on the \
                submission is approved by the same decision — a rejection is a statement about \
                specific evidence, not an indictment of the whole submission, and the buyer \
                resubmits only what failed. This mirrors how a tenant's verification decision \
                already cascades to its documents.

                Verification is manual: no registry is called, and the decision is recorded as \
                `manual_review` so a future automated check stays distinguishable from a human \
                reading a scan.""")
public record KycDecisionRequest(

        @Schema(description = "`approved` or `rejected`.", example = "approved", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String decision,

        @Schema(description = "Why. Required when rejecting; copied onto each failed document.",
                nullable = true)
        String reason,

        @Schema(description = "Which documents failed, as wire types (`nin`, `passport`, "
                + "`proof_of_address`). Required when rejecting, ignored when approving.",
                nullable = true)
        List<String> failedDocumentTypes
) {
}
