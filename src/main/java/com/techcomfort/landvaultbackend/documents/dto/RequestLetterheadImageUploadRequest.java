package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RequestLetterheadImageUploadRequest(
        @Schema(description = "`logo`, `signature` or `seal`.", example = "signature")
        @NotBlank
        String kind,
        @Schema(description = "The file's own name, kept only as a label.", example = "md-signature.png")
        @NotBlank
        String fileName,
        @Schema(description = "`image/png` (best for a signature or seal: keeps a transparent background) or `image/jpeg`.",
                example = "image/png")
        @NotBlank
        String contentType,
        @Schema(description = "Exact size in bytes; storage refuses any other size. At most 2,097,152 (2 MB).",
                example = "48211")
        @Positive
        long sizeBytes
) {
}
