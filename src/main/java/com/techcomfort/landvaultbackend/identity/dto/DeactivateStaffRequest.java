package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/portal/staff/{userId}/deactivate}. The reason goes to the audit log. */
public record DeactivateStaffRequest(@NotBlank @Size(max = 500) String reason) {
}
