package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record ApproveLetterTermsRequest(
        @Schema(description = "Optional; shown to the developer.")
        @Size(max = 2000)
        String note
) {
}
