package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.Size;

/** {@code POST /api/admin/onboarding-requests/{id}/contacted}. The note is optional ("called Monday, meeting Thursday"). */
public record OnboardingRequestContactedRequest(@Size(max = 2000) String note) {
}
