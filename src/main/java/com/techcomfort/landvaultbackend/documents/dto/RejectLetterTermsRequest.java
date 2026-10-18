package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectLetterTermsRequest(
        @Schema(description = "Required, and shown to the developer: what to change.",
                example = "Clause 4 names a charge. Declare it in the estate's cost disclosure instead and remove it here.")
        @NotBlank @Size(max = 2000)
        String note
) {
}
