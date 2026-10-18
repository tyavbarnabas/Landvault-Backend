package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ApproveLetterRequest(
        @Schema(description = """
                The `currentVersions` from the status you previewed against. If anything changed since, the \
                approval is refused (`LETTER_SETUP_CHANGED`) so you never approve a letter you didn't see.""")
        @NotNull
        LetterVersionsDto versions
) {
}
