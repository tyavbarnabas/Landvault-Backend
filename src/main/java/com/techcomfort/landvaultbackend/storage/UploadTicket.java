package com.techcomfort.landvaultbackend.storage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Permission for one upload: the browser sends the file with {@code method}
 * to {@code uploadUrl}, with exactly these {@code headers}, before
 * {@code expiresAt}, then the calling module confirms it by {@code fileId}.
 */
public record UploadTicket(
        UUID fileId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt
) {
}
