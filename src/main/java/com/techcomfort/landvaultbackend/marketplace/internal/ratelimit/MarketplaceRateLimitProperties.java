package com.techcomfort.landvaultbackend.marketplace.internal.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code landvault.marketplace.rate-limit.*}. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "landvault.marketplace.rate-limit")
public class MarketplaceRateLimitProperties {

    /**
     * Requests one client may make per window. 120 a minute is generous for
     * a person browsing (a feed page, a detail page and its map is three
     * requests) and far below what scraping the catalogue needs.
     */
    private int requestsPerWindow = 120;

    private Duration window = Duration.ofMinutes(1);

    /**
     * Bound on how many clients are tracked at once, so a flood of distinct
     * addresses can't grow the table without limit. Past it, expired
     * windows are swept; if it's still full, the table is cleared, which
     * errs towards letting requests through rather than failing closed on
     * everyone.
     */
    private int maxTrackedClients = 100_000;
}
