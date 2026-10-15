package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** {@code POST .../photos/uploads} body: what the browser is about to upload. */
@Schema(name = "RequestPhotoUploadRequest")
public record RequestPhotoUploadRequest(
        @Schema(description = "The file's own name, kept only as a label. Never shown on the marketplace.",
                example = "gate-and-road.jpg")
        @NotBlank
        String fileName,

        @Schema(description = "`image/jpeg`, `image/png` or `image/webp`.", example = "image/jpeg")
        @NotBlank
        String contentType,

        @Schema(description = "Exact size in bytes; storage refuses an upload of any other size. "
                + "At most 5,242,880 (5 MB).", example = "2483112")
        @Positive
        long sizeBytes
) {
}
