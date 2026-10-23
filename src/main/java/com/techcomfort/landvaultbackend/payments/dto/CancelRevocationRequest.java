package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Phase 5b: withdraw a notice of revocation. The note, if any, is shown to the buyer. */
@Schema(name = "CancelRevocationRequest")
public record CancelRevocationRequest(
        @Size(max = 500) @Schema(nullable = true, example = "We have agreed a date with the buyer.") String note
) {
}
