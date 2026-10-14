package com.techcomfort.landvaultbackend.storage.internal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * The connection to object storage. Missing configuration fails startup, the
 * same rule as JWT_SECRET and the mail host: a deployment that forgets it must
 * not boot and then fail on the first upload. Logs where it points, never a key.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

    @Bean
    S3Client storageClient(StorageProperties properties) {
        validate(properties);
        log.info("File storage at {} (public bucket '{}', private bucket '{}')",
                URI.create(properties.endpoint()).getHost(), properties.publicBucket(), properties.privateBucket());
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .forcePathStyle(properties.pathStyleAccess())
                // Only when the operation needs it: the SDK's default extra checksums
                // aren't understood by every S3-compatible store (R2, RustFS).
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.readTimeout()))
                .build();
    }

    @Bean
    S3Presigner storagePresigner(StorageProperties properties) {
        validate(properties);
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build())
                .build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }

    private static void validate(StorageProperties p) {
        require(p.endpoint(), "STORAGE_ENDPOINT");
        require(p.accessKey(), "STORAGE_ACCESS_KEY");
        require(p.secretKey(), "STORAGE_SECRET_KEY");
        require(p.publicBucket(), "STORAGE_PUBLIC_BUCKET");
        require(p.privateBucket(), "STORAGE_PRIVATE_BUCKET");
        require(p.publicBaseUrl(), "STORAGE_PUBLIC_BASE_URL");
        if (p.publicBucket().equals(p.privateBucket())) {
            throw new IllegalStateException("STORAGE_PUBLIC_BUCKET and STORAGE_PRIVATE_BUCKET are the same bucket. "
                    + "Private files (identity documents) must never share a bucket with public ones. "
                    + "Refusing to start.");
        }
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is not set. File storage needs it (see AGENTS.md, "
                    + "\"File storage\"). Refusing to start without it.");
        }
    }
}
