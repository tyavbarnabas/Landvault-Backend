package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code POST /api/portal/roles}: a company-made role. */
public record CreateRoleRequest(
        @Schema(example = "Accounts Clerk")
        @NotBlank @Size(max = 80) String name,
        @Schema(example = "Checks incoming payments; can see estates and prices.")
        @Size(max = 500) String description,
        @Schema(description = "`company` (company-wide staff only), `branch` (must be given with a branch) or "
                + "`either`.", example = "either")
        @NotBlank String scope,
        @Schema(description = "`portal.*` permission codes from `GET /api/portal/permissions`, all of which you hold.",
                example = "[\"portal.estates.view\", \"portal.payments.verify\"]")
        @NotEmpty @Size(max = MAX_PERMISSIONS) List<String> permissions
) {
    /**
     * A bound on the request, not a business limit: a company must always be able to make a role carrying
     * every portal permission, so CompanyRolesUnderRlsIT fails if the catalogue outgrows this.
     */
    public static final int MAX_PERMISSIONS = 100;
}
