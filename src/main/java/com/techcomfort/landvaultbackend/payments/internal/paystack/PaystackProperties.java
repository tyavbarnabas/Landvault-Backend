package com.techcomfort.landvaultbackend.payments.internal.paystack;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code landvault.paystack.*}. {@code secretKey} comes from
 * {@code PAYSTACK_SECRET_KEY} in the environment, never from a committed
 * file, and is never logged. {@code callbackUrl} is the frontend page
 * Paystack returns the buyer to. See AGENTS.md, "Payments".
 */
@ConfigurationProperties(prefix = "landvault.paystack")
public record PaystackProperties(
        String baseUrl,
        String secretKey,
        String callbackUrl,
        Duration connectTimeout,
        Duration readTimeout
) {

    public boolean isTestKey() {
        return secretKey != null && secretKey.startsWith("sk_test_");
    }
}
