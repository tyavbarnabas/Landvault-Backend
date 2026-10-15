package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One photo of an estate, as the portal sees it. */
@Schema(name = "EstatePhoto", description = """
        A photo of the estate. Public: while the estate is listed, the photo and its caption are on the \
        marketplace. Its location and camera metadata were removed when it was confirmed.""")
public record EstatePhotoDto(
        UUID id,

        @Schema(description = "Where the photo is served from. Public; safe to cache.")
        String url,

        String caption,

        @Schema(description = "0 for the first photo, then 1, 2, …")
        int position,

        @Schema(description = "The listing's main image on the marketplace. Exactly one photo is the cover "
                + "whenever the estate has any.")
        boolean cover,

        @Schema(description = "`jpeg`, `png` or `webp`, checked from the file itself.")
        String fileType,

        @Schema(description = "After metadata removal, so usually a little smaller than what was uploaded.")
        long sizeBytes,

        UUID uploadedBy,

        Instant uploadedAt
) {
}
