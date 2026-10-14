package com.techcomfort.landvaultbackend.tenancy.dto;

/** The one reply every accepted submission gets, whether or not it was stored; see AGENTS.md. */
public record OnboardingRequestReceivedResponse(String message) {
}
