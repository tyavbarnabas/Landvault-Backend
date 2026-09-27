package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/change-password} body.
 * <p>
 * {@code newPassword} carries exactly the constraint
 * {@link RegisterRequest#password()} does — {@code @NotBlank} — rather than
 * a second, stricter rule invented here, for the same reason
 * {@link ResetPasswordRequest} gives: a password acceptable at registration
 * cannot become unacceptable when it is changed.
 */
@Schema(
        name = "ChangePasswordRequest",
        description = """
                Change your own password by proving you know the current one.

                **This is not a reset.** A reset proves control of a mailbox; this proves knowledge \
                of the password being replaced. That difference is the entire point: a change-password \
                screen that emailed a code would be verifying the wrong thing, and would leave an \
                account whose mail is not yet configured — a freshly bootstrapped Super Admin, say — \
                unable to retire a temporary credential at all.""")
public record ChangePasswordRequest(

        @Schema(description = "The password being replaced. Verified, not merely accepted.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String currentPassword,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String newPassword
) {
}
