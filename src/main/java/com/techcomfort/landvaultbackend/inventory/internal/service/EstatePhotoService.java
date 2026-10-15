package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.dto.EstatePhotoDto;
import com.techcomfort.landvaultbackend.inventory.dto.ReorderPhotosRequest;
import com.techcomfort.landvaultbackend.inventory.dto.RequestPhotoUploadRequest;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstatePhoto;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstatePhotoRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.storage.FileType;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import com.techcomfort.landvaultbackend.storage.StorageException;
import com.techcomfort.landvaultbackend.storage.StorageVisibility;
import com.techcomfort.landvaultbackend.storage.StoredFileInfo;
import com.techcomfort.landvaultbackend.storage.UploadRequest;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An estate's photos: upload, caption, order, cover, delete. The bytes are
 * {@code storage}'s; this keeps which photos belong to which estate, in what
 * order. Every change takes the estate's row lock first, so the 20-photo
 * limit, the positions and the single cover can't be broken by two requests
 * at once. See AGENTS.md, "Estate photos".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstatePhotoService {

    /** Per estate. Checked when an upload is requested and again when it is confirmed. */
    public static final int MAX_PHOTOS = 20;
    /** 5 MB. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    static final String PURPOSE = "estate-photo";
    private static final Set<FileType> ALLOWED = Set.of(FileType.JPEG, FileType.PNG, FileType.WEBP);

    private final EstateRepository estates;
    private final EstatePhotoRepository photos;
    private final StorageApi storage;
    private final AuditApi auditApi;

    /**
     * Permission for one upload, into the public bucket, with metadata removal
     * asked for. Nothing belongs to the estate until it is confirmed.
     */
    @Transactional
    public UploadTicket requestUpload(UUID estateId, RequestPhotoUploadRequest request) {
        TenantScope scope = currentScope();
        Estate estate = writableEstate(estateId, scope, false);
        requireRoomForAnother(estateId);
        return storage.requestUpload(new UploadRequest(StorageVisibility.PUBLIC, PURPOSE, request.fileName(),
                request.contentType(), request.sizeBytes(), ALLOWED, MAX_BYTES, scope.userId(),
                estate.getTenantId(), true));
    }

    /**
     * Storage checks the file and removes its metadata; the photo then joins
     * the end of the estate's order, and becomes the cover if it is the first.
     * Confirming the same file again returns the photo already made from it.
     * <p>
     * {@code noRollbackFor}: storage marks a file it refuses as rejected and
     * deletes it, then throws. Rolling this transaction back would undo the
     * marking and leave a "pending" file whose bytes are gone — the trap
     * recorded for {@code StorageService.confirmUpload}, one level up.
     */
    @Transactional(noRollbackFor = StorageException.UploadRejected.class)
    public Confirmed confirm(UUID estateId, UUID fileId, String caption) {
        TenantScope scope = currentScope();
        Estate estate = writableEstate(estateId, scope, true);

        var existing = photos.findByFileId(fileId);
        if (existing.isPresent()) {
            if (!existing.get().getEstateId().equals(estateId)) {
                throw new StorageException.FileNotFound();
            }
            return new Confirmed(toDto(existing.get(), storage.find(fileId).orElseThrow()), false);
        }
        requireRoomForAnother(estateId);

        StoredFileInfo file = storage.confirmUpload(fileId, scope.userId());
        if (!PURPOSE.equals(file.purpose()) || file.visibility() != StorageVisibility.PUBLIC) {
            // Another kind of file the caller uploaded. Same answer as someone else's.
            throw new StorageException.FileNotFound();
        }

        int count = (int) photos.countByEstateId(estateId);
        EstatePhoto photo = photos.saveAndFlush(EstatePhoto.builder()
                .tenantId(estate.getTenantId())
                .branchId(estate.getBranchId())
                .estateId(estateId)
                .fileId(fileId)
                .caption(normalise(caption))
                .position(count)
                .cover(count == 0)
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.photo_added", "estate", estateId,
                estate.getTenantId(), "Photo added to '" + estate.getName() + "'"
                        + (photo.isCover() ? " as its cover." : ".")));
        log.info("Estate photo added: {} on estate {} by actor {}", photo.getId(), estateId, scope.userId());
        return new Confirmed(toDto(photo, file), true);
    }

    /** {@code created} is false when the file had already been confirmed as this photo. */
    public record Confirmed(EstatePhotoDto photo, boolean created) {
    }

    @Transactional(readOnly = true)
    public List<EstatePhotoDto> list(UUID estateId) {
        TenantScope scope = currentScope();
        estates.findByIdAndTenantId(estateId, requireTenant(scope)).orElseThrow(InventoryException.EstateNotFound::new);
        return toDtos(photos.findByEstateIdOrderByPositionAsc(estateId));
    }

    /** A caption left out stays; an empty one is cleared. Not audited: a caption is wording, not a change of what is shown. */
    @Transactional
    public EstatePhotoDto updateCaption(UUID estateId, UUID photoId, String caption) {
        TenantScope scope = currentScope();
        writableEstate(estateId, scope, true);
        EstatePhoto photo = photos.findByIdAndEstateId(photoId, estateId).orElseThrow(InventoryException.PhotoNotFound::new);
        if (caption != null && !Objects.equals(photo.getCaption(), normalise(caption))) {
            photo.setCaption(normalise(caption));
            photo = photos.save(photo);
        }
        return toDto(photo, storage.find(photo.getFileId()).orElseThrow());
    }

    /** The full set, each once, or nothing changes: a stale list must not silently drop or repeat a photo. */
    @Transactional
    public List<EstatePhotoDto> reorder(UUID estateId, ReorderPhotosRequest request) {
        TenantScope scope = currentScope();
        writableEstate(estateId, scope, true);
        List<EstatePhoto> current = photos.findByEstateIdOrderByPositionAsc(estateId);
        List<UUID> wanted = request.photoIds();
        Set<UUID> currentIds = current.stream().map(EstatePhoto::getId).collect(Collectors.toSet());
        if (wanted.size() != current.size() || !new HashSet<>(wanted).equals(currentIds)) {
            throw new InventoryException.RequestRejected("PHOTO_ORDER_MISMATCH",
                    "List every one of this estate's photos exactly once. The photos may have changed "
                            + "since you loaded them — reload and try again.");
        }
        Map<UUID, EstatePhoto> byId = current.stream().collect(Collectors.toMap(EstatePhoto::getId, Function.identity()));
        for (int i = 0; i < wanted.size(); i++) {
            byId.get(wanted.get(i)).setPosition(i);
        }
        photos.saveAll(current);
        return toDtos(photos.findByEstateIdOrderByPositionAsc(estateId));
    }

    @Transactional
    public List<EstatePhotoDto> setCover(UUID estateId, UUID photoId) {
        TenantScope scope = currentScope();
        Estate estate = writableEstate(estateId, scope, true);
        EstatePhoto target = photos.findByIdAndEstateId(photoId, estateId).orElseThrow(InventoryException.PhotoNotFound::new);
        if (!target.isCover()) {
            // The old cover is cleared and flushed first: one cover per estate is
            // a unique index, and Hibernate may otherwise write the new one first.
            for (EstatePhoto photo : photos.findByEstateIdOrderByPositionAsc(estateId)) {
                if (photo.isCover()) {
                    photo.setCover(false);
                    photos.saveAndFlush(photo);
                }
            }
            target.setCover(true);
            photos.saveAndFlush(target);
            auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.photo_cover_changed", "estate", estateId,
                    estate.getTenantId(), "Cover photo of '" + estate.getName() + "' changed."));
        }
        return toDtos(photos.findByEstateIdOrderByPositionAsc(estateId));
    }

    /**
     * The photo leaves the estate and its file is deleted from storage. The
     * rest close up; if it was the cover, the next in order takes over. A
     * listed estate left with no photo drops off the marketplace (unless it is
     * exempt) and returns when one is added, like any other condition.
     */
    @Transactional
    public void delete(UUID estateId, UUID photoId) {
        TenantScope scope = currentScope();
        Estate estate = writableEstate(estateId, scope, true);
        EstatePhoto photo = photos.findByIdAndEstateId(photoId, estateId).orElseThrow(InventoryException.PhotoNotFound::new);
        boolean wasCover = photo.isCover();
        photo.setDeleted(true);
        photo.setCover(false);
        photos.saveAndFlush(photo);

        List<EstatePhoto> remaining = photos.findByEstateIdOrderByPositionAsc(estateId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setPosition(i);
        }
        if (wasCover && !remaining.isEmpty()) {
            remaining.getFirst().setCover(true);
        }
        photos.saveAllAndFlush(remaining);

        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.photo_deleted", "estate", estateId,
                estate.getTenantId(), "Photo removed from '" + estate.getName() + "'"
                        + (wasCover && !remaining.isEmpty() ? "; the next photo is now the cover." : ".")));
        // Last, so nothing above can fail after the file is gone.
        storage.delete(photo.getFileId());
        log.info("Estate photo deleted: {} on estate {} by actor {}", photoId, estateId, scope.userId());
    }

    // --- shared ---

    private void requireRoomForAnother(UUID estateId) {
        if (photos.countByEstateId(estateId) >= MAX_PHOTOS) {
            throw new InventoryException.StateConflict("ESTATE_PHOTO_LIMIT_REACHED",
                    "An estate can have at most " + MAX_PHOTOS + " photos. Delete one to add another.");
        }
    }

    /** For writes — the caller's own estate, and the EB-2 branch rule. Locked when {@code lock}. */
    private Estate writableEstate(UUID estateId, TenantScope scope, boolean lock) {
        UUID tenantId = requireTenant(scope);
        Estate estate = (lock ? estates.findByIdAndTenantIdForUpdate(estateId, tenantId)
                : estates.findByIdAndTenantId(estateId, tenantId))
                .orElseThrow(InventoryException.EstateNotFound::new);
        EstateWriteAccess.requireWritable(estate);
        return estate;
    }

    private List<EstatePhotoDto> toDtos(List<EstatePhoto> list) {
        Map<UUID, StoredFileInfo> files = storage.findAll(list.stream().map(EstatePhoto::getFileId).toList())
                .stream().collect(Collectors.toMap(StoredFileInfo::id, Function.identity()));
        return list.stream()
                .filter(p -> files.containsKey(p.getFileId()))
                .map(p -> toDto(p, files.get(p.getFileId())))
                .toList();
    }

    private static EstatePhotoDto toDto(EstatePhoto photo, StoredFileInfo file) {
        return new EstatePhotoDto(photo.getId(), file.publicUrl(), photo.getCaption(), photo.getPosition(),
                photo.isCover(), file.fileType().getValue(), file.sizeBytes(), file.uploadedBy(), photo.getCreatedAt());
    }

    private static String normalise(String caption) {
        if (caption == null) {
            return null;
        }
        String trimmed = caption.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new InventoryException.EstateNotFound();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
