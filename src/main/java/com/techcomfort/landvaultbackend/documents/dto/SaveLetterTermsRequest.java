package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SaveLetterTermsRequest(
        @Schema(description = """
                The developer's own conditions, in order, 1 to 30, each under 600 characters, written as the \
                letter will print them. No amounts and no bank details: the disclosed fees are added \
                automatically, and buyers pay only through HomeTrue.""",
                example = "[\"That the land is to be used for the construction of a 4-bedroom terrace duplex.\", "
                        + "\"That building plans must be approved by the estate before work begins.\"]")
        @NotNull @Size(min = 1, max = 30)
        List<String> clauses
) {
}
