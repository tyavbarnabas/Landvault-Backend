package com.techcomfort.landvaultbackend.checkout.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code landvault.checkout.*}.
 * <p>
 * {@code holdDuration} is the 45-minute window of RS-1. It is configuration
 * rather than a constant so it can be shortened in a test, not so it can be
 * negotiated per buyer: <strong>a hold cannot be extended</strong> (RS-5),
 * and one window for everyone is the fair version — every minute added for
 * one buyer is a minute taken from whoever is waiting behind them.
 */
@ConfigurationProperties(prefix = "landvault.checkout")
public record CheckoutProperties(

        Duration holdDuration,

        /**
         * How often expired holds are swept. The plot is genuinely unheld
         * from {@code expiresAt}, not from the sweep — nothing can reserve
         * it in between, because the acquire checks {@code plots.status} and
         * the sweep is what sets that back. So this interval is how long a
         * released plot stays invisible to buyers, not a window in which two
         * people could hold it.
         */
        Duration sweepInterval
) {
}
