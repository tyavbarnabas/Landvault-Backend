package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs {@link InstalmentReminderService} once a day, in the morning in Lagos.
 * A thin trigger on its own bean, like the sweepers: it sets the platform
 * scope before the transaction begins (running plans span companies, and the
 * plots and estates they're read with are RLS-policied) and clears it after.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstalmentReminderJob {

    /** No user: the system did this. */
    private static final TenantScope PLATFORM_SCOPE = new TenantScope(null, null, null, true);

    private final InstalmentReminderService reminders;
    private final RestructureService restructures;

    @Scheduled(cron = "${landvault.instalments.reminder-cron}", zone = "Africa/Lagos")
    public void scheduledRun() {
        run(PlanStandings.today());
    }

    /**
     * Also called directly by tests. Returns how many reminders were claimed.
     * Phase 5c: also lapses restructure proposals past their last day, and
     * reminds buyers before they do — separately, so one failing never stops
     * the other.
     */
    public int run(LocalDate today) {
        TenantContext.set(PLATFORM_SCOPE);
        try {
            try {
                int lapsed = restructures.lapse(today);
                if (lapsed > 0) {
                    log.info("{} restructure proposal(s) lapsed on {}", lapsed, today);
                }
            } catch (RuntimeException e) {
                log.error("Restructure lapsing failed; will retry on the next run", e);
            }
            return reminders.remind(today);
        } catch (RuntimeException e) {
            // Swallowed: tomorrow's run picks up whatever was missed (the stages are windows).
            log.error("Instalment reminders failed; will retry on the next run", e);
            return 0;
        } finally {
            TenantContext.clear();
        }
    }
}
