package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The frontend's {@code PrimaryContact} — a specific individual's name,
 * role, work email, phone and government ID. On a tenant-detail read,
 * always {@code null}: nothing in the schema stores this data back out
 * again once {@code POST /api/admin/tenants} consumes it (not on
 * {@code Organization}, not anywhere else) — {@link TenantDetailDto#primaryContact()}
 * is always {@code null} rather than this type filled with nulls, per
 * AGENTS.md's "honest absence, never a plausible-looking placeholder" rule.
 * <p>
 * On the write side, this same shape is {@code POST /api/admin/tenants}'s
 * {@code primaryContact} field — this individual becomes the tenant's first
 * Executive Director account (see {@code TenantStaffAccountRequested}). The
 * validation annotations only apply there; they're inert on a response.
 */
public record PrimaryContactDto(
        @NotBlank String fullName,
        @NotBlank String roleTitle,
        @NotBlank @Email String workEmail,
        @NotBlank String phone,
        @NotBlank String govIdType,
        @NotBlank String govIdNumber
) {
}
