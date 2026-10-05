package com.techcomfort.landvaultbackend.identity.dto;

import java.time.Instant;

/** What the accept page shows before the person sets a password: who invited them, to do what. */
public record InvitationPreviewDto(
        String email,
        String firstName,
        String companyName,
        String roleName,
        String branchName,
        Instant expiresAt
) {
}
