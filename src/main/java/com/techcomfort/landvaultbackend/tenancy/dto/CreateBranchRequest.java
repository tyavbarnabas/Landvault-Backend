package com.techcomfort.landvaultbackend.tenancy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/portal/branches}. Only the name is required. The company is
 * always the caller's own, never a field. The office details are optional —
 * and <strong>public</strong>: they appear on this branch's marketplace listings.
 */
public record CreateBranchRequest(
        @NotBlank @Size(max = 255) String name,
        @Schema(description = "Office street address. Shown publicly on this branch's listings.")
        @Size(max = 255) String street,
        @Size(max = 128) String city,
        @Schema(description = "A Nigerian state — name, ISO code or common spelling (\"FCT\", \"Lagos State\").")
        String state,
        @Schema(description = "Office phone. Shown publicly on this branch's listings.")
        @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a phone number") String phone,
        @Schema(description = "Office email. Shown publicly on this branch's listings.")
        @Email @Size(max = 255) String email
) {
}
