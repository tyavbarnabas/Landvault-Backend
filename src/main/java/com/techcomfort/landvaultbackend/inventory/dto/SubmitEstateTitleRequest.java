package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** {@code PUT /api/portal/estates/{id}/title} body. Replaces whatever was declared before. */
@Schema(name = "SubmitEstateTitleRequest")
public record SubmitEstateTitleRequest(
        @Schema(description = "`C of O`, `R of O`, `Governor's Consent` or `Gazette`.", example = "C of O")
        @NotBlank
        String titleType,

        @Schema(example = "FCT/ABU/2019/1234")
        @NotBlank
        @Size(max = 100)
        String titleNumber,

        @Schema(description = "`YYYY-MM-DD`, optional, not in the future.", example = "2019-05-14")
        String issuedDate,

        @Schema(description = "Required: a confirmed `title_document` of this estate.")
        UUID titleDocumentFileId,

        @Schema(description = "Required: a confirmed `survey_plan` of this estate.")
        UUID surveyPlanFileId,

        @Schema(description = "Optional: a confirmed `deed` of this estate.")
        UUID deedFileId
) {
}
