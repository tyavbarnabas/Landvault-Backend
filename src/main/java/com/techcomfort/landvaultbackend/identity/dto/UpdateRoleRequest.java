package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code PUT /api/portal/roles/{code}}. Every field optional — left out means unchanged. */
public record UpdateRoleRequest(
        @Size(max = 80) String name,
        @Schema(description = "A blank description clears it.")
        @Size(max = 500) String description,
        @Schema(description = "Replaces the role's permissions; must not be empty.")
        @Size(max = CreateRoleRequest.MAX_PERMISSIONS) List<String> permissions,
        @Schema(description = "Can't be changed — accepted only if it equals the current scope. Create a new role "
                + "instead.")
        String scope
) {
}
