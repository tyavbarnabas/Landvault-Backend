package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Phase 5c: the developer declines a request. The reason is shown to the buyer. */
@Schema(name = "RejectRestructureRequest")
public record RejectRestructureRequest(
        @NotBlank @Size(max = 500) @Schema(example = "We can't extend beyond the original 12 months.") String reason
) {
}
