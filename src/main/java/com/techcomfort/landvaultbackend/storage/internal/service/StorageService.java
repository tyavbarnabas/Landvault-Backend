package com.techcomfort.landvaultbackend.storage.internal.service;

import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.FileType;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import com.techcomfort.landvaultbackend.storage.StorageException;
import com.techcomfort.landvaultbackend.storage.StorageVisibility;
import com.techcomfort.landvaultbackend.storage.StoredFileInfo;
import com.techcomfort.landvaultbackend.storage.UploadRequest;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import com.techcomfort.landvaultbackend.storage.internal.config.StorageProperties;
import com.techcomfort.landvaultbackend.storage.internal.domain.StoredFile;
import com.techcomfort.landvaultbackend.storage.internal.enums.StoredFileStatus;
import com.techcomfort.landvaultbackend.storage.internal.repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** {@link StorageApi}. See AGENTS.md, "File storage". */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService implements StorageApi {

    private static final Pattern PURPOSE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[\\p{Cntrl}\"\\\\/]");

    private final StoredFileRepository files;
    private final ObjectStore objects;
    private final StorageProperties properties;

    @Override
    @Transactional
    public UploadTicket requestUpload(UploadRequest request) {
        Objects.requireNonNull(request.visibility(), "visibility");
        Objects.requireNonNull(request.ownerUserId(), "ownerUserId");
        if (request.purpose() == null || request.purpose().length() > 40 || !PURPOSE.matcher(request.purpose()).matches()) {
            throw new IllegalArgumentException("purpose must be lowercase words joined by dashes");
        }
        Set<FileType> allowed = request.allowedTypes();
        FileType type = FileType.fromMimeType(request.contentType())
                .filter(allowed::contains)
                .orElseThrow(() -> new StorageException.FileTypeNotAllowed(describe(allowed)));
        long limit = Math.min(request.maxBytes(), properties.maxUploadSize().toBytes());
        if (request.sizeBytes() > limit) {
            throw new StorageException.FileTooLarge(limit);
        }
        if (request.sizeBytes() <= 0) {
            throw new StorageException.UploadRejected("FILE_EMPTY", "That file is empty.");
        }

        Instant now = Instant.now();
        String bucket = bucketFor(request.visibility());
        // A fresh random key per upload, never reused: a replaced file gets a new
        // key, so a cached copy can never be mistaken for the new one.
        String key = request.purpose() + "/" + UUID.randomUUID() + type.extension();
        StoredFile file = files.save(StoredFile.builder()
                .tenantId(request.tenantId())
                .visibility(request.visibility())
                .bucket(bucket)
                .objectKey(key)
                .purpose(request.purpose())
                .fileName(safeFileName(request.fileName(), type))
                .fileType(type)
                .sizeBytes(request.sizeBytes())
                .status(StoredFileStatus.PENDING)
                .stripMetadata(request.stripImageMetadata() && type != FileType.PDF)
                .uploadedBy(request.ownerUserId())
                .uploadLinkExpiresAt(now.plus(properties.uploadLinkTtl()))
                .build());

        ObjectStore.SignedUpload signed = objects.signUpload(bucket, key, type.mimeType(), request.sizeBytes(),
                properties.uploadLinkTtl());
        return new UploadTicket(file.getId(), signed.url(), "PUT", signed.headers(), signed.expiresAt());
    }

    /**
     * {@code noRollbackFor}: a rejected upload is deleted from storage and
     * marked rejected, then refused. Rolling that back would leave a file
     * marked pending whose object is already gone. Same trap as refresh()
     * and resetPassword(); see AGENTS.md.
     */
    @Override
    @Transactional(noRollbackFor = StorageException.UploadRejected.class)
    public StoredFileInfo confirmUpload(UUID fileId, UUID ownerUserId) {
        StoredFile file = files.findByIdForUpdate(fileId)
                .filter(f -> f.getUploadedBy().equals(ownerUserId))
                .orElseThrow(StorageException.FileNotFound::new);
        switch (file.getStatus()) {
            case READY -> {
                return toInfo(file);
            }
            case EXPIRED -> throw new StorageException.UploadExpired();
            case REJECTED, DELETED -> throw new StorageException.FileNotReady();
            case PENDING -> {
            }
        }
        if (file.getCreatedAt().plus(properties.pendingTtl()).isBefore(Instant.now())) {
            throw new StorageException.UploadExpired();
        }

        ObjectStore.ObjectHead head = objects.head(file.getBucket(), file.getObjectKey())
                .orElseThrow(StorageException.UploadNotReceived::new);
        if (head.sizeBytes() != file.getSizeBytes()) {
            reject(file, "UPLOAD_SIZE_MISMATCH", "The file that arrived isn't the size that was promised. Upload it again.");
        }
        if (!file.getFileType().mimeType().equalsIgnoreCase(head.contentType())) {
            reject(file, "UPLOAD_CONTENT_MISMATCH", "The file that arrived isn't the type that was promised. Upload it again.");
        }
        byte[] start = objects.readStart(file.getBucket(), file.getObjectKey(), FileType.SIGNATURE_BYTES);
        if (!file.getFileType().matches(start)) {
            reject(file, "UPLOAD_CONTENT_MISMATCH",
                    "That file isn't really " + label(file.getFileType()) + ". Upload a real one.");
        }
        if (file.isStripMetadata()) {
            removeMetadata(file);
        }

        file.setStatus(StoredFileStatus.READY);
        file.setConfirmedAt(Instant.now());
        return toInfo(files.save(file));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFileInfo> find(UUID fileId) {
        return files.findById(fileId).map(this::toInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredFileInfo> findAll(Collection<UUID> fileIds) {
        return fileIds.isEmpty() ? List.of() : files.findAllById(fileIds).stream().map(this::toInfo).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID fileId) {
        StoredFile file = files.findById(fileId).orElseThrow(StorageException.FileNotFound::new);
        if (file.getStatus() != StoredFileStatus.READY) {
            throw new StorageException.FileNotReady();
        }
        // Images open in the browser; anything else downloads. The type is the
        // one checked at confirmation, never what the uploader's browser said.
        ContentDisposition.Builder disposition = file.getFileType() == FileType.PDF
                ? ContentDisposition.attachment() : ContentDisposition.inline();
        ObjectStore.SignedUpload signed = objects.signDownload(file.getBucket(), file.getObjectKey(),
                file.getFileType().mimeType(),
                disposition.filename(file.getFileName(), StandardCharsets.UTF_8).build().toString(),
                properties.downloadLinkTtl());
        return new DownloadLink(signed.url(), signed.expiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] read(UUID fileId) {
        StoredFile file = files.findById(fileId).orElseThrow(StorageException.FileNotFound::new);
        if (file.getStatus() != StoredFileStatus.READY) {
            throw new StorageException.FileNotReady();
        }
        return objects.readAll(file.getBucket(), file.getObjectKey());
    }

    @Override
    @Transactional
    public void delete(UUID fileId) {
        StoredFile file = files.findByIdForUpdate(fileId).orElseThrow(StorageException.FileNotFound::new);
        if (file.getStatus() == StoredFileStatus.DELETED) {
            return;
        }
        objects.delete(file.getBucket(), file.getObjectKey());
        file.setStatus(StoredFileStatus.DELETED);
        file.setRemovedAt(Instant.now());
        files.save(file);
    }

    /** Pending uploads older than {@code pending-ttl}: never confirmed, so nobody is using them. */
    @Transactional(readOnly = true)
    public List<UUID> abandonedUploads() {
        return files.findIdsByStatusCreatedBefore(StoredFileStatus.PENDING, Instant.now().minus(properties.pendingTtl()));
    }

    /** Deletes whatever may have arrived for an abandoned upload. False if it was confirmed meanwhile. */
    @Transactional
    public boolean expire(UUID fileId) {
        StoredFile file = files.findByIdForUpdate(fileId).orElse(null);
        if (file == null || file.getStatus() != StoredFileStatus.PENDING) {
            return false;
        }
        objects.delete(file.getBucket(), file.getObjectKey());
        file.setStatus(StoredFileStatus.EXPIRED);
        file.setRemovedAt(Instant.now());
        files.save(file);
        return true;
    }

    /**
     * Rewrites the image without its metadata (see {@link ImageMetadataStripper})
     * under a NEW key, then deletes the original. Never overwritten in place: the
     * uploader could have fetched the original from the public bucket before
     * confirming, and a CDN that cached it would go on serving the metadata at
     * the same address. A file that can't be parsed is refused, never kept as is.
     */
    private void removeMetadata(StoredFile file) {
        byte[] original = objects.readAll(file.getBucket(), file.getObjectKey());
        byte[] cleaned;
        try {
            cleaned = ImageMetadataStripper.strip(file.getFileType(), original);
        } catch (ImageMetadataStripper.Unreadable e) {
            reject(file, "IMAGE_UNREADABLE", "That image couldn't be read. Export it again as "
                    + label(file.getFileType()) + " and upload it again.");
            return;
        }
        String oldKey = file.getObjectKey();
        String newKey = file.getPurpose() + "/" + UUID.randomUUID() + file.getFileType().extension();
        objects.put(file.getBucket(), newKey, file.getFileType().mimeType(), cleaned);
        file.setObjectKey(newKey);
        file.setSizeBytes((long) cleaned.length);
        objects.delete(file.getBucket(), oldKey);
    }

    private void reject(StoredFile file, String code, String message) {
        objects.delete(file.getBucket(), file.getObjectKey());
        file.setStatus(StoredFileStatus.REJECTED);
        file.setRemovedAt(Instant.now());
        files.save(file);
        log.info("Upload {} rejected ({})", file.getId(), code);
        throw new StorageException.UploadRejected(code, message);
    }

    private String bucketFor(StorageVisibility visibility) {
        return visibility == StorageVisibility.PUBLIC ? properties.publicBucket() : properties.privateBucket();
    }

    private StoredFileInfo toInfo(StoredFile file) {
        String publicUrl = file.getVisibility() == StorageVisibility.PUBLIC && file.getStatus() == StoredFileStatus.READY
                ? stripTrailingSlash(properties.publicBaseUrl()) + "/" + file.getObjectKey()
                : null;
        return new StoredFileInfo(file.getId(), file.getVisibility(), file.getPurpose(), file.getFileType(),
                file.getFileName(), file.getSizeBytes(), file.getStatus().value(), publicUrl, file.getUploadedBy(),
                file.getCreatedAt(), file.getConfirmedAt());
    }

    /** The person's own name for the file, for display only: no path, no control characters, at most 255. */
    static String safeFileName(String name, FileType type) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = UNSAFE_NAME_CHARS.matcher(base).replaceAll("").strip();
        if (base.isEmpty()) {
            base = "file" + type.extension();
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static String describe(Set<FileType> allowed) {
        return allowed.stream().sorted().map(t -> t.name().equals("JPEG") ? "JPG" : t.name())
                .collect(Collectors.joining(", "));
    }

    private static String label(FileType type) {
        return switch (type) {
            case JPEG -> "a JPG image";
            case PNG -> "a PNG image";
            case WEBP -> "a WebP image";
            case PDF -> "a PDF";
        };
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
