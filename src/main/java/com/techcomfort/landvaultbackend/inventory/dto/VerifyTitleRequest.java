package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** {@code POST /api/admin/title-reviews/{titleId}/verify} body. */
@Schema(name = "VerifyTitleRequest")
public record VerifyTitleRequest(
        @Schema(description = "`manual_review` (you compared the documents) or `registry_search` (an official "
                + "search at the land registry, report in hand).", example = "manual_review")
        @NotBlank
        String verificationSource,

        @Schema(description = "Optional; the developer sees it.")
        @Size(max = 2000)
        String note,

        @Schema(description = "The `submittedAt` of the title you reviewed. If the developer has resubmitted "
                + "since, the decision is refused (`TITLE_CHANGED`).")
        @NotNull
        Instant submittedAt
) {
}
