package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = """
        A company's or branch's allocation-letter wording. `current` is the newest saved version (with HomeTrue's \
        review of it); letters use `approved`, the newest version HomeTrue approved. A branch's approved template \
        replaces the company's for that branch's estates; until it has one, the company's applies (`inUse`).""")
public record LetterTemplateDto(
        @Schema(description = "`company` or `branch`.", example = "company")
        String source,
        UUID branchId,
        String branchName,
        @Schema(description = "Null until the first save.")
        LetterTemplateVersionDto current,
        @Schema(description = "Null when no version is approved yet.")
        LetterTemplateVersionDto approved,
        @Schema(description = """
                Whose wording this letterhead's letters print today: `branch` (its own approved template), \
                `company` (the company's approved one) or `default` (HomeTrue's wording).""", example = "company")
        String inUse
) {
}
