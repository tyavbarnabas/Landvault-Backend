package com.techcomfort.landvaultbackend.payments.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * In case a {@code refund.*} webhook was lost, asks Paystack about refunds
 * accepted but not finished for {@code landvault.refunds.stuck-after}. No
 * platform scope needed: {@code refunds} and {@code payments} aren't
 * RLS-policied. Each refund in its own transaction; exceptions never escape.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundSweeper {

    private final RefundOutcomeService outcomes;

    @Scheduled(fixedDelayString = "${landvault.refunds.sweep-interval}",
            initialDelayString = "${landvault.refunds.sweep-interval}")
    public void scheduledSweep() {
        sweep();
    }

    public Map<RefundOutcomeService.Outcome, Integer> sweep() {
        Map<RefundOutcomeService.Outcome, Integer> counts = new EnumMap<>(RefundOutcomeService.Outcome.class);
        try {
            for (UUID refundId : outcomes.stuck()) {
                try {
                    counts.merge(outcomes.refresh(refundId), 1, Integer::sum);
                } catch (RuntimeException e) {
                    log.warn("Could not check refund {} this sweep; will retry: {}", refundId, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.error("Refund sweep failed; will retry on the next run", e);
        }
        return counts;
    }
}
