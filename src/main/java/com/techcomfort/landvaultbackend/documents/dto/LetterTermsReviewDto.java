package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
        One version of an estate's allocation conditions, as HomeTrue's reviewer sees it: the developer's clauses, \
        the ones HomeTrue adds from the cost disclosure (for context — they aren't under review), and the version \
        letters use today, to compare against.""")
public record LetterTermsReviewDto(
        @Schema(description = "The version's id: what approve and reject are called on.")
        UUID termsId,
        UUID estateId,
        String estateName,
        String estateState,
        String companyName,
        int version,
        @Schema(description = "The developer's clauses — what is under review. Look for amounts and bank details "
                + "the automatic check missed: every figure must come from the cost disclosure.")
        List<String> clauses,
        Instant savedAt,
        List<String> generatedClauses,
        @Schema(description = "`pending`, `approved`, `rejected` or `superseded`.", example = "pending")
        String status,
        String note,
        Instant reviewedAt,
        @Schema(description = "The version letters use today, or null when none is approved.")
        Integer approvedVersion,
        @Schema(description = "That version's clauses, to compare against; empty when there is none.")
        List<String> approvedClauses
) {
}
