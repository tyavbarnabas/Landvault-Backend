package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "The company's letterhead, or one branch's: what it sets itself, and what a letter would print.")
public record LetterheadDto(
        @Schema(description = "`company` or `branch`.", example = "branch")
        String scope,
        @Schema(description = "Null for the company's letterhead.")
        UUID branchId,
        String branchName,
        @Schema(description = "Goes up on every change. 0 when nothing has been set yet.", example = "3")
        int version,
        Instant updatedAt,
        @Schema(description = "This letterhead's own signatory name. Null when it sets none (a branch then uses the company's).")
        String signatoryName,
        String signatoryTitle,
        @Schema(description = "This letterhead's own images; null where it sets none.")
        LetterheadImageDto logo,
        LetterheadImageDto signature,
        LetterheadImageDto seal,
        PrintedLetterheadDto printed,
        @Schema(description = """
                What a letter still needs before it can be issued under this letterhead: `signatory_name`, \
                `signatory_title`, `signature`. Empty when complete. The logo and seal are optional — a company \
                seal has been optional in Nigeria since CAMA 2020.""",
                example = "[\"signature\"]")
        List<String> missing
) {
}
