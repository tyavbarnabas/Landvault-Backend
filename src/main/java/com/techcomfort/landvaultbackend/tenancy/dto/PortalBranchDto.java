package com.techcomfort.landvaultbackend.tenancy.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One of the caller's own company's branches. Office fields are null when not
 * entered — never filled from the company's own address.
 */
public record PortalBranchDto(
        UUID id,
        String name,
        String street,
        String city,
        String state,
        String phone,
        String email,
        Instant createdAt
) {
}
