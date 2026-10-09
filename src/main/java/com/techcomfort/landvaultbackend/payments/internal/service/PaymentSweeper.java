package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs {@link PaymentSweepService} on a schedule. A thin trigger on its own
 * bean, like the reservation sweeper: it sets the platform scope before any
 * transaction begins and clears it after, and each purchase is settled in its
 * own transaction so one failure never stops the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSweeper {

    /** No user: the system did this, and the audit trail says so. */
    private static final TenantScope PLATFORM_SCOPE = new TenantScope(null, null, null, true);

    private final PaymentSweepService sweep;

    @Scheduled(
            fixedDelayString = "${landvault.payments.sweep-interval}",
            initialDelayString = "${landvault.payments.sweep-interval}")
    public void scheduledSweep() {
        sweep();
    }

    /** Also called directly by tests and the dev-only trigger. Returns how many purchases ended each way. */
    public Map<PaymentSweepService.Outcome, Integer> sweep() {
        Map<PaymentSweepService.Outcome, Integer> outcomes = new EnumMap<>(PaymentSweepService.Outcome.class);
        TenantContext.set(PLATFORM_SCOPE);
        try {
            for (UUID transactionId : sweep.candidates()) {
                try {
                    outcomes.merge(sweep.settle(transactionId), 1, Integer::sum);
                } catch (RuntimeException e) {
                    // One purchase failing (Paystack down) must not stop the rest; it is retried next run.
                    log.warn("Could not settle purchase {} this sweep; will retry: {}", transactionId, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // Swallowed: an exception escaping a fixed-delay task cancels every future run.
            log.error("Payment sweep failed; will retry on the next run", e);
        } finally {
            TenantContext.clear();
        }
        return outcomes;
    }
}
