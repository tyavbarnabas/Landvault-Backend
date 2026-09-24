package com.techcomfort.landvaultbackend.kyc.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code landvault.kyc.*} config. {@code encryptionKey} has no default — a
 * missing key fails startup rather than silently writing an NIN in plaintext,
 * the same rule as {@code JWT_SECRET} and {@code TOTP_ENCRYPTION_KEY}.
 * <p>
 * A <strong>separate</strong> key from {@code TOTP_ENCRYPTION_KEY}, not a
 * reuse of it: the two protect different things for different parties, and
 * one leaked key should not yield both every buyer's NIN and the ability to
 * mint second factors for every account.
 */
@ConfigurationProperties(prefix = "landvault.kyc")
public record KycProperties(String encryptionKey) {
}
