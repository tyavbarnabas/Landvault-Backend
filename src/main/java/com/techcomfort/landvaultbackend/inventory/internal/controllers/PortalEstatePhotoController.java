package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.inventory.dto.ConfirmPhotoRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstatePhotoDto;
import com.techcomfort.landvaultbackend.inventory.dto.ReorderPhotosRequest;
import com.techcomfort.landvaultbackend.inventory.dto.RequestPhotoUploadRequest;
import com.techcomfort.landvaultbackend.inventory.dto.SetCoverPhotoRequest;
import com.techcomfort.landvaultbackend.inventory.dto.UpdatePhotoRequest;
import com.techcomfort.landvaultbackend.inventory.internal.service.EstatePhotoService;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** An estate's photos. See {@link EstatePhotoService} and AGENTS.md, "Estate photos". */
@RestController
@RequestMapping("/api/portal/estates/{estateId}/photos")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_ESTATES)
public class PortalEstatePhotoController {

    private final EstatePhotoService service;

    @Operation(summary = "Start a photo upload",
            description = """
                    Requires `portal.estates.manage`. Returns a link the browser uploads the file to directly \
                    (`PUT` with exactly the returned headers, within 10 minutes), then call `.../{fileId}/confirm`.

                    - JPG, PNG or WebP only (`FILE_TYPE_NOT_ALLOWED`); at most **5,242,880 bytes** (5 MB, \
                    `FILE_TOO_LARGE`). `sizeBytes` must be exact — storage refuses any other size.
                    - At most **20 photos** per estate (409 `ESTATE_PHOTO_LIMIT_REACHED`), checked here and \
                    again at confirmation.
                    - Photos are **public**: shown on the marketplace while the estate is listed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Upload permitted"),
            @ApiResponse(responseCode = "400", description = "`FILE_TYPE_NOT_ALLOWED`, `FILE_TOO_LARGE`", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`ESTATE_READ_ONLY_FOR_BRANCH`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`ESTATE_PHOTO_LIMIT_REACHED`", content = @Content())
    })
    @PostMapping("/uploads")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<UploadTicket> requestUpload(@PathVariable UUID estateId,
                                                      @Valid @RequestBody RequestPhotoUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestUpload(estateId, request));
    }

    @Operation(summary = "Confirm an uploaded photo",
            description = """
                    Requires `portal.estates.manage`, and must be called by the person who uploaded it. Storage \
                    checks what arrived (size, and the type by its first bytes), then **removes the image's \
                    metadata** — GPS position, camera, dates — keeping only its orientation, so the URL and \
                    `sizeBytes` differ from the upload's. The photo joins the end of the order; the first photo \
                    becomes the cover. Confirming again returns the same photo (200).""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Photo added"),
            @ApiResponse(responseCode = "200", description = "Already confirmed; the existing photo"),
            @ApiResponse(responseCode = "400", description = "`UPLOAD_SIZE_MISMATCH`, `UPLOAD_CONTENT_MISMATCH`, `IMAGE_UNREADABLE` — the file was deleted; upload again", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`, `FILE_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`ESTATE_PHOTO_LIMIT_REACHED`, `UPLOAD_NOT_RECEIVED`, `UPLOAD_EXPIRED`", content = @Content())
    })
    @PostMapping("/{fileId}/confirm")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstatePhotoDto> confirm(@PathVariable UUID estateId, @PathVariable UUID fileId,
                                                  @Valid @RequestBody(required = false) ConfirmPhotoRequest request) {
        EstatePhotoService.Confirmed result = service.confirm(estateId, fileId, request == null ? null : request.caption());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.photo());
    }

    @Operation(summary = "An estate's photos, in order",
            description = "Requires `portal.estates.view`. Exactly one is the cover whenever there are any.")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<List<EstatePhotoDto>> list(@PathVariable UUID estateId) {
        return ResponseEntity.ok(service.list(estateId));
    }

    @Operation(summary = "Change a photo's caption",
            description = "Requires `portal.estates.manage`. Left out: unchanged. Empty: cleared. At most 200 characters.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`, `PHOTO_NOT_FOUND`", content = @Content())
    })
    @PatchMapping("/{photoId}")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstatePhotoDto> update(@PathVariable UUID estateId, @PathVariable UUID photoId,
                                                 @Valid @RequestBody UpdatePhotoRequest request) {
        return ResponseEntity.ok(service.updateCaption(estateId, photoId, request.caption()));
    }

    @Operation(summary = "Reorder the photos",
            description = "Requires `portal.estates.manage`. Every photo id exactly once, in the new order "
                    + "(400 `PHOTO_ORDER_MISMATCH` otherwise). The cover doesn't change. Returns the photos in order.")
    @PutMapping("/order")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<List<EstatePhotoDto>> reorder(@PathVariable UUID estateId,
                                                        @Valid @RequestBody ReorderPhotosRequest request) {
        return ResponseEntity.ok(service.reorder(estateId, request));
    }

    @Operation(summary = "Choose the cover photo",
            description = "Requires `portal.estates.manage`. The cover is the listing's main image on the "
                    + "marketplace. Returns the photos in order.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`, `PHOTO_NOT_FOUND`", content = @Content())
    })
    @PutMapping("/cover")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<List<EstatePhotoDto>> setCover(@PathVariable UUID estateId,
                                                         @Valid @RequestBody SetCoverPhotoRequest request) {
        return ResponseEntity.ok(service.setCover(estateId, request.photoId()));
    }

    @Operation(summary = "Delete a photo",
            description = """
                    Requires `portal.estates.manage`. The file is deleted from storage. The others close up; if \
                    it was the cover, the next in order becomes the cover. A listed estate left with no photo \
                    leaves the marketplace until one is added (estates listed before photos were required are \
                    exempt). A copy a CDN or browser already cached may be served for a while longer.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`, `PHOTO_NOT_FOUND`", content = @Content())
    })
    @DeleteMapping("/{photoId}")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<Void> delete(@PathVariable UUID estateId, @PathVariable UUID photoId) {
        service.delete(estateId, photoId);
        return ResponseEntity.noContent().build();
    }
}
