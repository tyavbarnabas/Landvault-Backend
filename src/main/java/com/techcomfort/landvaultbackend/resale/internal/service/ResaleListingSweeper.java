package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.resale.internal.enums.ListingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sell my plot, phase 2: once a day, takes listings down after 90 days or
 * when a rule says so (arrears, a notice, the estate off the marketplace, an
 * early sale's price below the agreed one). Platform scope, set and cleared
 * like the other sweepers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResaleListingSweeper {

    /** No user: the system did this. */
    private static final TenantScope PLATFORM_SCOPE = new TenantScope(null, null, null, true);

    private final ResaleListingService listings;

    @Scheduled(cron = "${landvault.resale.sweep-cron}", zone = "Africa/Lagos")
    public void scheduledSweep() {
        sweep(Instant.now());
    }

    /** Also called by tests and the dev-only trigger. Returns how many listings ended each way. */
    public Map<ListingStatus, Integer> sweep(Instant now) {
        Map<ListingStatus, Integer> outcomes = new EnumMap<>(ListingStatus.class);
        TenantContext.set(PLATFORM_SCOPE);
        try {
            for (UUID id : listings.liveListingIds()) {
                try {
                    outcomes.merge(listings.sweep(id, now), 1, Integer::sum);
                } catch (RuntimeException e) {
                    log.warn("Could not sweep resale listing {} this run; will retry: {}", id, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // Swallowed: an exception escaping a scheduled task would stop nothing here, but log it loudly.
            log.error("Resale listing sweep failed; will retry on the next run", e);
        } finally {
            TenantContext.clear();
        }
        return outcomes;
    }
}
