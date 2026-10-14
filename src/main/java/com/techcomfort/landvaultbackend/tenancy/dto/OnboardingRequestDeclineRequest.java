package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/admin/onboarding-requests/{id}/decline}. Kept for the record; never sent to the company. */
public record OnboardingRequestDeclineRequest(@NotBlank @Size(max = 1000) String reason) {
}
