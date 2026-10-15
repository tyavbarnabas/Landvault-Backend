package com.techcomfort.landvaultbackend.tenancy.internal.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;

/** {@code landvault.onboarding.*}: the public "request onboarding" form. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "landvault.onboarding")
public class OnboardingProperties {

    /** Where new requests are announced. */
    private String alertTo;

    private String fromAddress;

    /** Promised in the confirmation email: "our team will contact you {responseTime}". */
    private String responseTime = "within 2 working days";

    /**
     * Submissions one address may make per window. A security parameter: each
     * accepted one emails HomeTrue and the address typed in, so this is what
     * stops the form being a way to flood either inbox.
     */
    private int submissionsPerWindow = 5;

    private Duration window = Duration.ofHours(1);

    /** Bound on tracked addresses; see MarketplaceRateLimitProperties for the same reasoning. */
    private int maxTrackedClients = 100_000;

    /** A {@code new} request older than this many working days (Monday to Friday) needs attention. */
    private int attentionAfterWorkingDays = 2;

    /** Whose Monday to Friday: working days are counted in this time zone. */
    private ZoneId timeZone = ZoneId.of("Africa/Lagos");
}
