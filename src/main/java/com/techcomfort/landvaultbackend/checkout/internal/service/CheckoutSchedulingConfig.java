package com.techcomfort.landvaultbackend.checkout.internal.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler, for {@link ReservationExpirySweeper} — the
 * first and only scheduled work in this application.
 * <p>
 * Lives with the job it exists for rather than on the application class, so
 * that whoever deletes the last scheduled task also finds the switch that
 * enabled it.
 */
@Configuration
@EnableScheduling
public class CheckoutSchedulingConfig {
}
