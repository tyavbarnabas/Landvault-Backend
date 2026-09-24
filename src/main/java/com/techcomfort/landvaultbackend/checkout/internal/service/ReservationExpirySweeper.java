package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns abandoned holds to the pool (RS-3) — <strong>the first scheduled
 * job in this system</strong>, which makes it the first thing to meet a
 * consequence AGENTS.md wrote down before anything could hit it:
 * <p>
 * <em>"it cannot rely on 'no context means see everything'. Any background
 * process that needs real data access has to explicitly establish a scope …
 * There is no ambient 'system' identity that sees past RLS today."</em>
 * <p>
 * So this sets a platform scope for the duration of the sweep, exactly as
 * {@code TenantContextFilter} does for a request, and clears it in a
 * {@code finally}. Without that, every policied table this job touches now
 * or later returns nothing and the sweep silently releases zero holds — a
 * leak of inventory nobody would get an error about. The plot write itself
 * goes through the {@code SECURITY DEFINER} release function regardless, so
 * it is belt and braces rather than a single point of failure.
 * <p>
 * Expiry must not depend on the buyer's browser: a closed laptop still
 * returns the plot, which is the whole reason this is a server-side job and
 * not a client-side timer.
 * <p>
 * Deliberately a thin trigger around {@link ReservationService#releaseExpired()}.
 * The scope has to be established <em>before</em> the transaction begins —
 * {@code TenantScopedDataSource} issues its {@code SET LOCAL} statements
 * when the connection turns off autocommit — so the transactional work has
 * to sit behind a call to another bean, not in a self-invoked method on
 * this one.
 * <p>
 * <strong>Per-instance.</strong> Behind more than one server every instance
 * runs this, and two sweeps could pick up the same expired hold. That is
 * harmless rather than wrong — the release is idempotent and the reservation
 * row is only closed once — but it is wasted work, and a shared scheduler
 * lock is the answer if this is ever scaled out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpirySweeper {

    /** No user: the system did this, and the audit trail says so. */
    private static final TenantScope PLATFORM_SCOPE = new TenantScope(null, null, null, true);

    private final ReservationService reservationService;

    @Scheduled(
            fixedDelayString = "${landvault.checkout.sweep-interval}",
            initialDelayString = "${landvault.checkout.sweep-interval}")
    public void sweep() {
        TenantContext.set(PLATFORM_SCOPE);
        try {
            reservationService.releaseExpired();
        } catch (RuntimeException e) {
            // Swallowed on purpose: an exception escaping a scheduled method
            // cancels all future runs of a fixed-delay task, which would turn
            // one bad sweep into holds that never expire again.
            log.error("Reservation expiry sweep failed; will retry on the next run", e);
        } finally {
            TenantContext.clear();
        }
    }
}
