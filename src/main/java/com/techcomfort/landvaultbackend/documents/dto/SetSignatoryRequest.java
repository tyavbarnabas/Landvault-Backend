package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetSignatoryRequest(
        @Schema(description = "The person who signs letters.", example = "Adaeze Okafor")
        @NotBlank @Size(max = 120)
        String name,
        @Schema(description = "Their title as printed under the signature.", example = "Managing Director")
        @NotBlank @Size(max = 120)
        String title
) {
}
