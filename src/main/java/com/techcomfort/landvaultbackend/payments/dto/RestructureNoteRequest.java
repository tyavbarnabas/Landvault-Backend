package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Phase 5c: a reason or note shown to the buyer — required to reject a request, optional to withdraw a proposal. */
@Schema(name = "RestructureNoteRequest")
public record RestructureNoteRequest(
        @Size(max = 500) @Schema(nullable = true, example = "We can offer 12 months, not 24.") String note
) {
}
