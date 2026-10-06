package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A role a company can give its staff, as the portal's role pickers need it. */
public record AssignableRoleDto(
        String code,
        String name,
        String description,
        @Schema(description = "company (must be company-wide), branch (must name a branch) or either")
        String scope,
        @Schema(description = "The permission slugs the role carries.")
        List<String> permissions,
        @Schema(description = "Whether you can invite someone into this role or give it to them directly — "
                + "true only if you hold every permission it carries. A branch request is checked against "
                + "the approver instead, so a branch manager may request a role they can't grant.")
        boolean canGrant,
        @Schema(description = "False for the platform's standard roles. Reserved for company-made roles, "
                + "which don't exist yet.")
        boolean custom
) {
}
