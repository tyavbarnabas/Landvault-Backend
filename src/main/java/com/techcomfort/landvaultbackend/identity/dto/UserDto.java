package com.techcomfort.landvaultbackend.identity.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.time.Instant;
import java.util.UUID;

/**
 * Public projection of a user account — the only shape of a user another
 * module is allowed to see.
 * <p>
 * Never carries {@code passwordHash}, {@code twoFaSecret}, or any other
 * credential material; those never leave the identity module. {@code status}
 * is the enum's wire value (e.g. {@code "active"}) rather than the internal
 * {@code UserStatus} type itself, since that type lives in
 * {@code identity.internal} and a public DTO must not expose an internal
 * type through its own signature.
 */
public record UserDto(
        UUID id,
        UUID tenantId,
        UUID branchId,
        String firstName,
        String lastName,
        String email,
        String phone,
        String country,
        Currency currency,
        String status,
        Instant createdAt
) {
}
