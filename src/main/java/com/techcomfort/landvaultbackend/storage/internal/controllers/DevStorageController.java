package com.techcomfort.landvaultbackend.storage.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.FileType;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import com.techcomfort.landvaultbackend.storage.StorageException;
import com.techcomfort.landvaultbackend.storage.StorageVisibility;
import com.techcomfort.landvaultbackend.storage.StoredFileInfo;
import com.techcomfort.landvaultbackend.storage.UploadRequest;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import com.techcomfort.landvaultbackend.storage.internal.service.AbandonedUploadSweeper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/**
 * Development only: drives the storage module by hand against RustFS, before any
 * real feature uses it. Not present outside the dev profile at all. Files are
 * the caller's own; nothing here decides who else may see one, which is the
 * real features' job.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/storage")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_DEV_STORAGE)
public class DevStorageController {

    private final StorageApi storage;
    private final AbandonedUploadSweeper sweeper;

    public record DevUploadRequest(
            @NotNull StorageVisibility visibility,
            String fileName,
            @NotBlank String contentType,
            @Positive long sizeBytes
    ) {
    }

    @Operation(summary = "DEV ONLY: start an upload",
            description = "Super Admin (`admin.tenants.manage`). Returns a link valid for minutes: PUT the file to "
                    + "`uploadUrl` with exactly `headers`, then confirm. The size and type are part of the link's "
                    + "signature. Any accepted type (JPG, PNG, WebP, PDF), up to the platform limit. `visibility` is "
                    + "`public` or `private` (either case).")
    @PostMapping("/uploads")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<UploadTicket> requestUpload(@Valid @RequestBody DevUploadRequest body) {
        TenantScope scope = scope();
        return ResponseEntity.status(201).body(storage.requestUpload(new UploadRequest(body.visibility(), "dev-test",
                body.fileName(), body.contentType(), body.sizeBytes(), EnumSet.allOf(FileType.class), Long.MAX_VALUE,
                scope.userId(), scope.tenantId())));
    }

    @Operation(summary = "DEV ONLY: confirm an upload",
            description = "Checks the file arrived, is the promised size, and really is the promised type by its "
                    + "first bytes. A mismatch deletes it (`UPLOAD_SIZE_MISMATCH` / `UPLOAD_CONTENT_MISMATCH`).")
    @PostMapping("/uploads/{fileId}/confirm")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<StoredFileInfo> confirm(@PathVariable UUID fileId) {
        return ResponseEntity.ok(storage.confirmUpload(fileId, scope().userId()));
    }

    @Operation(summary = "DEV ONLY: a short-lived link to one of your files")
    @GetMapping("/files/{fileId}/download-link")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<DownloadLink> downloadLink(@PathVariable UUID fileId) {
        own(fileId);
        return ResponseEntity.ok(storage.downloadLink(fileId));
    }

    @Operation(summary = "DEV ONLY: delete one of your files")
    @DeleteMapping("/files/{fileId}")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<Void> delete(@PathVariable UUID fileId) {
        own(fileId);
        storage.delete(fileId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "DEV ONLY: clean up abandoned uploads now",
            description = "Uploads never confirmed within `landvault.storage.pending-ttl` are deleted and marked expired.")
    @PostMapping("/sweep")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<Map<String, Integer>> sweep() {
        return ResponseEntity.ok(Map.of("expired", sweeper.sweep()));
    }

    private void own(UUID fileId) {
        UUID me = scope().userId();
        storage.find(fileId).filter(f -> f.uploadedBy().equals(me)).orElseThrow(StorageException.FileNotFound::new);
    }

    private static TenantScope scope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException("No tenant scope on an authenticated request"));
    }
}
