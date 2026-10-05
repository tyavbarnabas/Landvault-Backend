package com.techcomfort.landvaultbackend.tenancy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /api/portal/branches/{id}}. Every field optional: left out means
 * unchanged, blank clears it — except {@code name}, which can't be cleared.
 */
public record UpdateBranchRequest(
        @Size(max = 255) String name,
        @Size(max = 255) String street,
        @Size(max = 128) String city,
        @Schema(description = "A Nigerian state, or blank to clear it.") String state,
        @Pattern(regexp = "^$|^\\+?[0-9 ()-]{7,20}$", message = "must be a phone number") String phone,
        @Email @Size(max = 255) String email
) {
}
