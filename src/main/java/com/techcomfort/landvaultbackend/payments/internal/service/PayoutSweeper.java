package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * TR-3: in case a {@code transfer.*} webhook was lost, asks Paystack about
 * payouts with no reply for {@code stuck-sending-after} or not finished for
 * {@code stuck-pending-after}. A thin trigger, like the payment sweeper: the
 * platform scope is set before any transaction begins, and each payout is
 * refreshed in its own transaction so one failure never stops the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutSweeper {

    private static final TenantScope PLATFORM_SCOPE = new TenantScope(null, null, null, true);

    private final PayoutOutcomeService outcomes;

    @Scheduled(fixedDelayString = "${landvault.payouts.sweep-interval}",
            initialDelayString = "${landvault.payouts.sweep-interval}")
    public void scheduledSweep() {
        sweep();
    }

    /** Also called directly by tests. Returns how many payouts ended each way. */
    public Map<PayoutOutcomeService.Outcome, Integer> sweep() {
        Map<PayoutOutcomeService.Outcome, Integer> counts = new EnumMap<>(PayoutOutcomeService.Outcome.class);
        TenantContext.set(PLATFORM_SCOPE);
        try {
            for (String reference : outcomes.stuckReferences()) {
                try {
                    counts.merge(outcomes.refresh(reference), 1, Integer::sum);
                } catch (RuntimeException e) {
                    log.warn("Could not check payout {} this sweep; will retry: {}", reference, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // Swallowed: an exception escaping a fixed-delay task cancels every future run.
            log.error("Payout sweep failed; will retry on the next run", e);
        } finally {
            TenantContext.clear();
        }
        return counts;
    }
}
