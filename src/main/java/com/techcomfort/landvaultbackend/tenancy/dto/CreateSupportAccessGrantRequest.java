package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /api/admin/tenants/{id}/support-access} body. Deliberately
 * has no {@code grantedToUserId}/{@code actor} field, even though the real
 * frontend's {@code requestSupportAccess} takes an {@code actor} parameter
 * — that's a mock-mode-only convenience (see the real call in
 * {@code tenantsService.ts}: {@code apiClient.post(..., { reason })}, no
 * actor sent). The real grantee is always the authenticated caller, never
 * client-supplied — same principle as the verification reviewer's identity
 * in slice B1.
 */
public record CreateSupportAccessGrantRequest(
        @NotBlank String reason,
        @Positive Integer durationMinutes
) {
}
