package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.internal.repository.RefreshTokenRepository;
import com.techcomfort.landvaultbackend.identity.internal.security.RefreshTokenProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Deletes refresh tokens well past expiry — rotation writes a row per
 * refresh and nothing else removes them. Rows are kept for
 * {@code cleanupRetention} after expiry so a recent theft incident can still
 * be investigated.
 * <p>
 * Unlike {@code ReservationExpirySweeper}, this sets no platform scope:
 * {@code refresh_tokens} carries no RLS policy, so there is nothing for a
 * scope to unlock. The chain-safety rule lives in the query — see
 * {@link RefreshTokenRepository#deleteExpiredBefore}.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenProperties properties;

    @Scheduled(
            fixedDelayString = "${app.refresh-token.cleanup-interval}",
            initialDelayString = "${app.refresh-token.cleanup-interval}")
    public void run() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            // Swallowed: an exception escaping a fixed-delay task cancels
            // every future run.
            log.error("Refresh token cleanup failed; will retry on the next run", e);
        }
    }

    public int deleteExpired() {
        int deleted = refreshTokenRepository.deleteExpiredBefore(Instant.now().minus(properties.cleanupRetention()));
        if (deleted > 0) {
            log.info("Deleted {} refresh tokens expired for more than {}", deleted, properties.cleanupRetention());
        }
        return deleted;
    }
}
