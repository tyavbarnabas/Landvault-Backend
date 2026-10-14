package com.techcomfort.landvaultbackend.storage.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * {@code landvault.storage.*}. The endpoint and keys come from the environment
 * and differ per deployment (RustFS locally, R2 or S3 when deployed); the keys
 * are never logged. {@code publicBaseUrl} is where public files are served
 * from, a domain separate from the app's own. See AGENTS.md, "File storage".
 */
@ConfigurationProperties(prefix = "landvault.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        String publicBucket,
        String privateBucket,
        String publicBaseUrl,
        Duration uploadLinkTtl,
        Duration downloadLinkTtl,
        Duration pendingTtl,
        DataSize maxUploadSize,
        Duration connectTimeout,
        Duration readTimeout
) {
}
