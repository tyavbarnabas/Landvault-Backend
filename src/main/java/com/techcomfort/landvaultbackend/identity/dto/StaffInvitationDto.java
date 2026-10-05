package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** An invitation as the inviting company sees it. Never carries the token. */
public record StaffInvitationDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String roleCode,
        String roleName,
        UUID branchId,
        String branchName,
        @Schema(description = "awaiting_approval, rejected, pending, expired, accepted or revoked") String status,
        Instant createdAt,
        Instant expiresAt,
        Instant acceptedAt,
        Instant revokedAt,
        int sendCount,
        @Schema(description = "True when a branch manager requested it and a company-wide approver had to approve it.")
        boolean approvalRequired,
        @Schema(description = "Who invited — or, for a request, who asked.") UUID requestedBy,
        Instant approvedAt,
        Instant rejectedAt,
        String rejectionReason
) {
}
