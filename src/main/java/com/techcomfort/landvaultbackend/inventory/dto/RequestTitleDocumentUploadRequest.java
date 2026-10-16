package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** {@code POST .../title/documents/uploads} body. */
@Schema(name = "RequestTitleDocumentUploadRequest")
public record RequestTitleDocumentUploadRequest(
        @Schema(description = "The file's own name, kept as a label for downloads.", example = "c-of-o.pdf")
        @NotBlank
        String fileName,

        @Schema(description = "`application/pdf`, `image/jpeg` or `image/png`.", example = "application/pdf")
        @NotBlank
        String contentType,

        @Schema(description = "Exact size in bytes; storage refuses an upload of any other size. "
                + "At most 10,485,760 (10 MB).", example = "1048576")
        @Positive
        long sizeBytes,

        @Schema(description = "`title_document`, `survey_plan` or `deed`.", example = "title_document")
        @NotBlank
        String kind
) {
}
