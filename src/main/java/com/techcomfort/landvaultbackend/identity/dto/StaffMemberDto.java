package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A member of the caller's company, as the portal's staff screen shows them. */
public record StaffMemberDto(
        UUID userId,
        String firstName,
        String lastName,
        String email,
        String phone,
        @Schema(description = "active, pending_verification, suspended or deactivated") String status,
        List<StaffRoleDto> roles,
        Instant lastLoginAt,
        Instant createdAt
) {
    /** One role assignment. {@code branchId} null means company-wide. */
    public record StaffRoleDto(String roleCode, String roleName, UUID branchId, String branchName) {
    }
}
