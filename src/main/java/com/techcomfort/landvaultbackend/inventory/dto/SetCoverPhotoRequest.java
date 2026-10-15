package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code PUT .../photos/cover} body. */
@Schema(name = "SetCoverPhotoRequest")
public record SetCoverPhotoRequest(
        @NotNull
        UUID photoId
) {
}
