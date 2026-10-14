package com.techcomfort.landvaultbackend.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored file as other modules see it. {@code publicUrl} is set only for a
 * public file that has been confirmed; a private file is reached through
 * {@link StorageApi#downloadLink}.
 *
 * @param status {@code pending}, {@code ready}, {@code rejected}, {@code expired} or {@code deleted}
 */
public record StoredFileInfo(
        UUID id,
        StorageVisibility visibility,
        String purpose,
        FileType fileType,
        String fileName,
        long sizeBytes,
        String status,
        String publicUrl,
        UUID uploadedBy,
        Instant createdAt,
        Instant confirmedAt
) {

    public boolean ready() {
        return "ready".equals(status);
    }
}
