package com.techcomfort.landvaultbackend.storage.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Cleans up uploads started but never confirmed (a tab closed between upload
 * and confirm): deletes whatever arrived, marks the file expired. Done here
 * rather than with a bucket lifecycle rule so it behaves the same on RustFS, R2
 * and S3, and the record says what happened. No platform scope needed:
 * {@code stored_files} isn't RLS-policied. Each file in its own transaction;
 * exceptions never escape (one would cancel every future run).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbandonedUploadSweeper {

    private final StorageService storage;

    @Scheduled(fixedDelayString = "${landvault.storage.sweep-interval}",
            initialDelayString = "${landvault.storage.sweep-interval}")
    public void scheduledSweep() {
        sweep();
    }

    /** How many abandoned uploads were cleaned up. */
    public int sweep() {
        int expired = 0;
        try {
            for (UUID fileId : storage.abandonedUploads()) {
                try {
                    if (storage.expire(fileId)) {
                        expired++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not clean up upload {} this sweep; will retry ({})", fileId, e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.error("Upload cleanup failed; will retry on the next run", e);
        }
        if (expired > 0) {
            log.info("Cleaned up {} abandoned upload(s)", expired);
        }
        return expired;
    }
}
