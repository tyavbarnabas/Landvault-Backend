package com.techcomfort.landvaultbackend.storage.internal.service;

import com.techcomfort.landvaultbackend.storage.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The only class that talks to object storage. Signing a link is local and
 * makes no network call; head, read and delete do, and a failure there is
 * {@link StorageException.StorageUnavailable}. Never logs a signed link.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ObjectStore {

    private final S3Client s3;
    private final S3Presigner presigner;

    record SignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
    }

    record ObjectHead(long sizeBytes, String contentType) {
    }

    /**
     * The type and exact size are part of the signature, so storage refuses an
     * upload of anything else. The headers the browser must send come back;
     * Host and Content-Length are left out because the browser sets those itself.
     */
    SignedUpload signUpload(String bucket, String key, String contentType, long sizeBytes, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).contentLength(sizeBytes)
                .build();
        PresignedPutObjectRequest signed = presigner.presignPutObject(r -> r.signatureDuration(ttl).putObjectRequest(put));
        Map<String, String> headers = new LinkedHashMap<>();
        signed.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new SignedUpload(signed.url().toString(), headers, signed.expiration());
    }

    /** A download link that makes the browser treat the file as exactly this type, under this name. */
    SignedUpload signDownload(String bucket, String key, String contentType, String contentDisposition, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket).key(key)
                .responseContentType(contentType)
                .responseContentDisposition(contentDisposition)
                .build();
        PresignedGetObjectRequest signed = presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(get));
        return new SignedUpload(signed.url().toString(), Map.of(), signed.expiration());
    }

    /** Empty when nothing is stored under that key. */
    Optional<ObjectHead> head(String bucket, String key) {
        try {
            HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(key));
            return Optional.of(new ObjectHead(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("head", e);
        } catch (SdkException e) {
            throw unavailable("head", e);
        }
    }

    /** The first bytes of the object, to see what it really is. */
    byte[] readStart(String bucket, String key, int bytes) {
        try {
            return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key).range("bytes=0-" + (bytes - 1))).asByteArray();
        } catch (SdkException e) {
            throw unavailable("read", e);
        }
    }

    /** The whole object. Only for files small enough to hold in memory (images being cleaned). */
    byte[] readAll(String bucket, String key) {
        try {
            return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
        } catch (SdkException e) {
            throw unavailable("read", e);
        }
    }

    /** Stores bytes the server produced itself (a cleaned image). */
    void put(String bucket, String key, String contentType, byte[] bytes) {
        try {
            s3.putObject(r -> r.bucket(bucket).key(key).contentType(contentType).contentLength((long) bytes.length),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw unavailable("write", e);
        }
    }

    /** Idempotent: deleting a key that isn't there succeeds. */
    void delete(String bucket, String key) {
        try {
            s3.deleteObject(r -> r.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw unavailable("delete", e);
        }
    }

    private static StorageException.StorageUnavailable unavailable(String action, SdkException e) {
        // The exception class only: storage messages can echo the request, and a signed one carries a credential.
        log.warn("Object storage {} failed ({})", action, e.getClass().getSimpleName());
        return new StorageException.StorageUnavailable();
    }
}
