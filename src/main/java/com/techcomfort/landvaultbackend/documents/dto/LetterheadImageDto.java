package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "One letterhead image: a private file, reached through `GET /api/portal/letterheads/files/{fileId}/link`.")
public record LetterheadImageDto(
        @Schema(description = "`logo`, `signature` or `seal`.", example = "signature")
        String kind,
        UUID fileId,
        @Schema(description = "The uploaded file's own name, as a label.", example = "md-signature.png")
        String fileName,
        @Schema(description = "`png` or `jpeg`.", example = "png")
        String fileType,
        long sizeBytes,
        @Schema(description = "Whose it is: `company`, or `branch` for a branch's own.", example = "company")
        String source
) {
}
