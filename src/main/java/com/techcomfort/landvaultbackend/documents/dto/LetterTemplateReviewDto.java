package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
        One version of a company's or branch's letter template, as HomeTrue's reviewer sees it, with the version \
        letters use today to compare against.""")
public record LetterTemplateReviewDto(
        @Schema(description = "What approve and reject are called on.")
        UUID templateId,
        UUID tenantId,
        String companyName,
        @Schema(description = "`company` or `branch`.")
        String source,
        UUID branchId,
        String branchName,
        int version,
        String opening,
        String closing,
        String signOff,
        List<String> standardClauses,
        Instant savedAt,
        @Schema(description = "`pending`, `approved`, `rejected` or `superseded`.")
        String status,
        String note,
        Instant reviewedAt,
        @Schema(description = "The version letters use today; null when none is approved.")
        LetterTemplateVersionDto approved
) {
}
