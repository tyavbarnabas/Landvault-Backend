package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Phase 5c: the buyer asks for their remaining instalments to be spread differently. */
@Schema(name = "RequestRestructureRequest")
public record RequestRestructureRequest(
        @NotBlank @Size(max = 500)
        @Schema(description = "Shown to the developer.", example = "I lost my job in August; I can pay half each month.")
        String reason,
        @Min(1) @Max(60)
        @Schema(description = "How many instalments you'd like what's left spread over, if you have a number in mind.",
                nullable = true, example = "18")
        Integer preferredInstalments
) {
}
