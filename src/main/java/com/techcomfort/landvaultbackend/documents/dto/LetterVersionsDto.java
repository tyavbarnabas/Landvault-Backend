package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        The versions an estate's allocation letter is drawn from. `terms` is the HomeTrue-approved version of the \
        developer's clauses; `branchLetterhead` is null for a company-level estate and 0 when the branch sets \
        nothing of its own; the disclosure versions are null where nothing is declared. `templateSource` is whose \
        approved wording the letter uses — `default` (HomeTrue's), `company` or `branch` — and `template` its \
        version (null for the default).""")
public record LetterVersionsDto(
        Integer terms,
        Integer companyLetterhead,
        Integer branchLetterhead,
        Integer fees,
        Integer refundTerms,
        Integer defaultTerms,
        String templateSource,
        Integer template
) {
}
