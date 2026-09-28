package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code PUT .../blocks/{blockId}} body. A field left out stays as it is. */
@Schema(name = "UpdateBlockRequest", description = """
        Rename a block or change its label. Names stay unique within the estate; a clash \
        returns 409.""")
public record UpdateBlockRequest(
        @Schema(description = "Must not be blank when sent.", example = "Block D")
        String name,
        @Schema(description = "An empty string clears it.")
        String label
) {
}
