package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** {@code PATCH .../photos/{photoId}} body. A field left out stays as it is. */
@Schema(name = "UpdatePhotoRequest")
public record UpdatePhotoRequest(
        @Schema(description = "At most 200 characters. An empty string clears it.")
        @Size(max = 200)
        String caption
) {
}
