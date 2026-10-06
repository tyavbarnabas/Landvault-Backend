package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** {@code PUT /api/portal/staff/{userId}/role}: replaces every role the person holds with this one. */
public record ChangeStaffRoleRequest(
        @Schema(example = "sales_manager") @NotBlank String roleCode,
        @Schema(description = "Same rules as an invitation: required for branch_manager, refused for "
                + "executive_director, optional otherwise.")
        UUID branchId
) {
}
