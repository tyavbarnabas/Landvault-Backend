package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/portal/staff/invitations/{id}/reject}. The branch manager sees the reason. */
public record RejectInvitationRequest(
        @Schema(description = "Why the request was turned down; shown to the branch manager who asked.")
        @NotBlank @Size(max = 500) String reason
) {
}
