package com.techcomfort.landvaultbackend.marketplace.dto;

/**
 * One photo in a listing's gallery: the image and the caption the developer
 * wrote for it (use it as alt text). Nothing about who uploaded it, and the
 * file has had its location and camera metadata removed.
 */
public record MarketplacePhotoDto(String url, String caption) {
}
