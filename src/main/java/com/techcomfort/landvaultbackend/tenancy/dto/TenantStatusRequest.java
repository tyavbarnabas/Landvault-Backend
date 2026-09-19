package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/admin/tenants/{id}/status} body. {@code reason} is
 * required for {@code SUSPENDED}/{@code OFFBOARDED}, nullable for
 * {@code ACTIVE} — enforced in the service layer, not bean validation,
 * since the requirement depends on which status is being set. The real
 * frontend's {@code setTenantStatus} sends only {@code { status }}, no
 * reason — this is a deliberate divergence from the task spec, not an
 * oversight; see AGENTS.md.
 */
public record TenantStatusRequest(@NotBlank String status, String reason) {
}
