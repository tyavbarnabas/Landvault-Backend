package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record LetterTemplatesOverviewDto(
        LetterTemplateDto company,
        @Schema(description = "Each branch the caller can see.")
        List<LetterTemplateDto> branches,
        @Schema(description = "The fill-in fields a template may use.",
                example = "[\"buyerName\", \"plot\", \"estate\", \"location\", \"applicationDate\", \"allocationDate\"]")
        List<String> fields,
        @Schema(description = "HomeTrue's wording, used until the company has an approved template.")
        DefaultWording defaultWording
) {

    public record DefaultWording(String opening, String signOff) {
    }
}
