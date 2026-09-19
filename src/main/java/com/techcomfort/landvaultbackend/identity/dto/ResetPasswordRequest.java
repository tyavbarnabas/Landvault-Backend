package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/reset-password} body.
 * <p>
 * {@code newPassword} carries exactly the constraint
 * {@link RegisterRequest#password()} carries — {@code @NotBlank} — rather
 * than a second, stricter rule invented here: a password acceptable at
 * registration must stay acceptable at reset. Registration has no strength
 * rules beyond non-blank today; when real ones land they belong in one
 * shared constraint applied to both, not duplicated across these two records.
 */
public record ResetPasswordRequest(
        @NotBlank @Email String email,
        @NotBlank String code,
        @NotBlank String newPassword
) {
}
