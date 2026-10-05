package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /api/portal/staff/invitations}. The company is the caller's own,
 * never a field.
 */
public record CreateStaffInvitationRequest(
        @Schema(description = "A work email with no LandVault account yet.") @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String firstName,
        @NotBlank @Size(max = 255) String lastName,
        @Schema(description = "A tenant role code, e.g. branch_manager, sales_manager, surveyor_project_manager.",
                example = "branch_manager")
        @NotBlank String roleCode,
        @Schema(description = "Required for branch-only roles (branch_manager); must be left out for "
                + "company-only roles (executive_director); optional otherwise.")
        UUID branchId
) {
}
