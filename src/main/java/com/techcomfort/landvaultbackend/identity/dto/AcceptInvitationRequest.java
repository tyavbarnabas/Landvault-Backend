package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** SI-3: the invited person sets their own password — nobody else ever knows it. */
public record AcceptInvitationRequest(
        @NotBlank String token,
        @Schema(description = "The same rule as registration.") @NotBlank String password
) {
}
