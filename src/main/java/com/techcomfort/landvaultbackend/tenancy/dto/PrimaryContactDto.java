package com.techcomfort.landvaultbackend.tenancy.dto;

/**
 * The frontend's {@code PrimaryContact} — a specific individual's name,
 * role, work email, phone and government ID. Never populated in this
 * slice: nothing in the schema stores this data at all (not on
 * {@code Organization}, not anywhere else) — {@link TenantDetailDto#primaryContact()}
 * is always {@code null} rather than this type filled with nulls, per
 * AGENTS.md's "honest absence, never a plausible-looking placeholder" rule.
 * Kept as a real type (not deleted) so the shape is documented and ready
 * once a real source for this data exists.
 */
public record PrimaryContactDto(
        String fullName,
        String roleTitle,
        String workEmail,
        String phone,
        String govIdType,
        String govIdNumber
) {
}
