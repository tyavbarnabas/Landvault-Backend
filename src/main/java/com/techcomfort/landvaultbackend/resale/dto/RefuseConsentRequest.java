package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sell my plot, phase 4: the developer refuses consent to a resale. */
@Schema(name = "RefuseResaleConsent")
public record RefuseConsentRequest(
        @Schema(description = "Why. The seller reads it, so write it to them.")
        @NotBlank @Size(max = 1000) String reason) {
}
