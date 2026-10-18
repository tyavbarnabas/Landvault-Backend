package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The company's letterhead and every branch's the caller can see (a branch manager: their own).")
public record LetterheadOverviewDto(
        LetterheadDto company,
        List<LetterheadDto> branches
) {
}
