package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** One saved version of an estate's allocation conditions, with HomeTrue's decision on it. */
public record LetterTermsVersionDto(
        int version,
        List<String> clauses,
        Instant savedAt,
        @Schema(description = """
                `pending` (newest version, awaiting HomeTrue), `approved`, `rejected`, or `superseded` (replaced \
                by a newer version before HomeTrue decided it).""", example = "approved")
        String reviewStatus,
        @Schema(description = "HomeTrue's note: always present on a rejection, optional on an approval.")
        String reviewNote,
        Instant reviewedAt
) {
}
