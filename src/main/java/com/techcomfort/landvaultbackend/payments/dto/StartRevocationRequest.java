package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Phase 5b: give the buyer notice of revocation. The reason is shown to them. */
@Schema(name = "StartRevocationRequest")
public record StartRevocationRequest(
        @NotBlank @Size(max = 500)
        @Schema(description = "Shown to the buyer in the notice. Say what is overdue and since when, plainly.",
                example = "Instalments 3 and 4 have been unpaid since March despite our reminders.")
        String reason
) {
}
