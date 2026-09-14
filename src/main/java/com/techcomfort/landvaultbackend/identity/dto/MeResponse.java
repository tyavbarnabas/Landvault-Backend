package com.techcomfort.landvaultbackend.identity.dto;

import java.util.List;
import java.util.UUID;

/** {@code GET /api/me} — proves a bearer token authenticates, nothing more. */
public record MeResponse(UUID userId, String email, UUID tenantId, boolean platformStaff, List<String> permissions) {
}
