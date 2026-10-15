package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A permission a company may put in its own roles, as the role editor's checkboxes need it.
 * The list comes ordered by group, then within the group — both orders are HomeTrue's (changeset 085).
 */
public record PortalPermissionDto(
        String code,
        @Schema(description = "A short name for a checkbox.")
        String label,
        String description,
        @Schema(description = "Works only for company-wide staff, so it can go only in a `company` role.")
        boolean requiresCompanyWide,
        @Schema(description = "Whether you hold it. You can only put permissions you hold into a role.")
        boolean held,
        @Schema(description = "Stable key of its heading in the role editor. Always present.", example = "estates")
        String group,
        @Schema(description = "The heading to show. Always present.", example = "Estates")
        String groupLabel
) {
}
