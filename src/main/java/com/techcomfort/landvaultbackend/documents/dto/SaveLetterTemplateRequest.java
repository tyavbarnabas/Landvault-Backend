package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SaveLetterTemplateRequest(
        @Schema(description = """
                The opening paragraph. Must include {plot} and {estate}; may use {buyerName}, {location}, \
                {applicationDate} (the day the buyer reserved the plot) and {allocationDate}. No amounts or bank \
                details. Up to 1,500 characters.""",
                example = "With reference to your application dated {applicationDate} for a plot of land at {estate}, "
                        + "{location}, we are glad to convey to you the allocation of {plot}, subject to the following "
                        + "terms and conditions.")
        @NotNull
        String opening,
        @Schema(description = "Optional paragraph after the conditions. Same fill-in fields and rules; up to 1,000 characters.",
                example = "Our site office is open Monday to Saturday, 9am to 5pm, for any questions about your plot.")
        String closing,
        @Schema(description = "The line above \"Yours faithfully\". No fill-in fields; up to 80 characters.",
                example = "Congratulations.")
        @NotNull
        String signOff,
        @Schema(description = "Conditions printed on every letter, before the estate's own. 0 to 30, each under 600 "
                + "characters; same rules as an estate's conditions.")
        @Size(max = 30)
        List<String> standardClauses
) {
}
