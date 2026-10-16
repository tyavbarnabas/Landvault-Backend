package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One piece of title evidence. Private: open it through its download route, never a public URL. */
@Schema(name = "TitleDocumentDto")
public record TitleDocumentDto(
        UUID fileId,
        @Schema(description = "`title_document`, `survey_plan` or `deed`.")
        String kind,
        String fileName,
        @Schema(description = "`pdf`, `jpeg` or `png`.")
        String fileType,
        long sizeBytes,
        Instant uploadedAt
) {
}
