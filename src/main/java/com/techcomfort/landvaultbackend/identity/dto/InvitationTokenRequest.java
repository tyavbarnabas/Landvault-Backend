package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** The token from the link — sent in the body, never a URL. */
public record InvitationTokenRequest(@NotBlank String token) {
}
