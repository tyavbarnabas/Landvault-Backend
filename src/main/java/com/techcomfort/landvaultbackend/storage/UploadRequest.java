package com.techcomfort.landvaultbackend.storage;

import java.util.Set;
import java.util.UUID;

/**
 * What a module asks for before a browser uploads. The calling module decides
 * the rules for its own files (which types, how large) and who the owner is,
 * from the caller's session — never from the request body.
 *
 * @param purpose   a short lowercase label for the kind of file
 *                  ({@code estate-photo}); it becomes the storage folder
 * @param fileName  the name the person's file had, kept only for display and downloads
 * @param tenantId  the company the file belongs to, or null (a buyer's file)
 */
public record UploadRequest(
        StorageVisibility visibility,
        String purpose,
        String fileName,
        String contentType,
        long sizeBytes,
        Set<FileType> allowedTypes,
        long maxBytes,
        UUID ownerUserId,
        UUID tenantId
) {
}
