package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** {@code PUT .../photos/order} body. */
@Schema(name = "ReorderPhotosRequest")
public record ReorderPhotosRequest(
        @Schema(description = "Every one of the estate's photo ids, each once, in the new order. "
                + "Anything else is refused (`PHOTO_ORDER_MISMATCH`), so a stale list can't drop or duplicate a photo.")
        @NotNull
        List<UUID> photoIds
) {
}
