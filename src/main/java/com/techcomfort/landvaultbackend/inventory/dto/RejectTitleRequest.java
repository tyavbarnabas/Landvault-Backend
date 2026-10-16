package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** {@code POST /api/admin/title-reviews/{titleId}/reject} body. */
@Schema(name = "RejectTitleRequest")
public record RejectTitleRequest(
        @Schema(description = "Why, in words the developer can act on. They see it.",
                example = "The survey plan is for a different parcel — its coordinates don't match the estate.")
        @NotBlank
        @Size(max = 2000)
        String note,

        @Schema(description = "The `submittedAt` of the title you reviewed (see verify).")
        @NotNull
        Instant submittedAt
) {
}
