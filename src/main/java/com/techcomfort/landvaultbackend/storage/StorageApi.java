package com.techcomfort.landvaultbackend.storage;

import java.util.Optional;
import java.util.UUID;

/**
 * Upload, confirm, serve and remove files. Every method that acts for a person
 * takes their user id: a file is reachable only by the person who uploaded it,
 * unless the calling module has decided otherwise and asks without one
 * ({@link #find}, {@link #downloadLink}, {@link #delete}). See AGENTS.md,
 * "File storage".
 */
public interface StorageApi {

    /**
     * Checks the request against the caller's own rules and records a pending
     * file. Nothing is in storage yet.
     *
     * @throws StorageException.FileTypeNotAllowed  the type isn't one the caller allows
     * @throws StorageException.FileTooLarge        bigger than the caller's limit, or the platform's
     */
    UploadTicket requestUpload(UploadRequest request);

    /**
     * After the browser's upload: checks the file really arrived, is the size
     * promised and really is the type it claimed (by its first bytes). A file
     * that isn't is deleted from storage and the file marked rejected.
     * Confirming a ready file again returns it unchanged.
     *
     * @throws StorageException.FileNotFound      no such file, or someone else's
     * @throws StorageException.UploadNotReceived nothing has arrived yet; try again after uploading
     * @throws StorageException.UploadExpired     too late; request a new upload
     * @throws StorageException.UploadRejected    the size or content didn't match
     */
    StoredFileInfo confirmUpload(UUID fileId, UUID ownerUserId);

    /** Any file, whoever uploaded it. For the calling module, which decides who may see it. */
    Optional<StoredFileInfo> find(UUID fileId);

    /**
     * A link to a ready file that expires in minutes. For a private file this
     * is the only way in; the calling module checks who may have it first.
     *
     * @throws StorageException.FileNotFound  no such file
     * @throws StorageException.FileNotReady  not confirmed, or removed
     */
    DownloadLink downloadLink(UUID fileId);

    /** Removes the file from storage. The record stays, marked deleted. A no-op for one already gone. */
    void delete(UUID fileId);
}
