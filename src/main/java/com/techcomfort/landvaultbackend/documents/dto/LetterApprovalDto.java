package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
        Whether an estate's allocation letter is approved. An approval records the versions the previewed letter \
        was drawn from, and stands only while all of them are still current — a change to the conditions, the \
        letterhead (signatory, signature, seal, logo), the letter template or the cost disclosure withdraws it.""")
public record LetterApprovalDto(
        UUID estateId,
        String estateName,
        @Schema(description = "True only while the latest approval's versions are all still current.")
        boolean approved,
        @Schema(description = "When the latest approval was given; null if never, or withdrawn since.")
        Instant approvedAt,
        @Schema(description = "What the latest approval saw; null if never approved, or withdrawn since.")
        LetterVersionsDto approvedVersions,
        @Schema(description = "What a letter would be drawn from now. Send these back to approve.")
        LetterVersionsDto currentVersions,
        @Schema(description = """
                What has changed since the latest approval, so it no longer stands: `terms`, `company_letterhead`, \
                `branch_letterhead`, `fees`, `refund_terms`, `default_terms`, `template`. Empty when approved or never approved.""",
                example = "[\"fees\"]")
        List<String> changedSinceApproval,
        @Schema(description = """
                What stops approval now: `no_approved_terms` (HomeTrue hasn't approved any version of the \
                conditions yet), `signatory_name`, `signatory_title`, `signature` (missing from the letterhead this \
                estate prints under). Empty when it can be approved.""", example = "[\"signature\"]")
        List<String> blockers,
        @Schema(description = "True when the newest version of the conditions is still awaiting HomeTrue's review: "
                + "the letter uses the approved one meanwhile.")
        boolean newerTermsAwaitingReview
) {
}
