package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/portal/estates/{id}/verification-checks} body.
 * <p>
 * {@code status} defaults to {@code NOT_CHECKED} when omitted, and
 * <strong>NOT_CHECKED must never render as positive</strong> — a check that
 * hasn't happened is not a clean bill of health.
 * <p>
 * {@code verificationSource} is what makes the row worth more than a boolean:
 * a green badge from a registry lookup and one from a human reading a PDF are
 * different claims.
 */
public record CreateVerificationCheckRequest(
        @NotBlank String checkType,
        String status,
        String verificationSource,
        String notes
) {
}
