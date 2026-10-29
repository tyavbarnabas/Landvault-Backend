package com.techcomfort.landvaultbackend.marketplace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One item of the public feed: an estate ({@code primary}) or a plot a buyer
 * is selling ({@code resale}), the frontend's own tagged shape.
 */
@Schema(description = "`listingType` says which shape `data` is: `primary` → an estate, `resale` → a resale plot.")
public record MarketplaceFeedItemDto(
        String listingType,
        @Schema(oneOf = {MarketplaceListingDto.class, MarketplaceResaleListingDto.class})
        Object data
) {
}
