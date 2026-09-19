package com.techcomfort.landvaultbackend.identity.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code landvault.otp.*} config. Defaults live in {@code application.yml};
 * every value here is a security parameter, so changing one is a deliberate
 * decision — see AGENTS.md for why each limit exists.
 */
@ConfigurationProperties(prefix = "landvault.otp")
public record OtpProperties(
        Duration codeTtl,
        int maxAttempts,
        int rateLimitMaxRequests,
        Duration rateLimitWindow,
        // Sender address for EmailOtpDeliveryService — configuration, never
        // hardcoded in the sending code.
        String fromAddress
) {
}
