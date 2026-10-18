package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** One saved version of a letter template, with HomeTrue's decision on it. */
public record LetterTemplateVersionDto(
        int version,
        String opening,
        String closing,
        String signOff,
        List<String> standardClauses,
        Instant savedAt,
        @Schema(description = "`pending`, `approved`, `rejected` or `superseded`.", example = "approved")
        String reviewStatus,
        String reviewNote,
        Instant reviewedAt
) {
}
