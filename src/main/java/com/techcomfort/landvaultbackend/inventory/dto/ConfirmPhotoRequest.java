package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** {@code POST .../photos/{fileId}/confirm} body. The body itself may be left out. */
@Schema(name = "ConfirmPhotoRequest")
public record ConfirmPhotoRequest(
        @Schema(description = "Optional. Shown with the photo and used as its alt text — describe what it shows. "
                + "At most 200 characters.", example = "The main gate and the paved access road")
        @Size(max = 200)
        String caption
) {
}
