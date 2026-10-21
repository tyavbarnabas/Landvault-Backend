package com.techcomfort.landvaultbackend.identity.dto;

import com.techcomfort.landvaultbackend.common.PostalAddress;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A buyer's address as stored; {@code null} parts were not given. */
public record AddressResponse(
        String line1,
        String line2,
        String city,
        @Schema(description = "For Nigeria, the canonical state name.")
        String state,
        String postalCode,
        @Schema(description = "Two-letter country code.")
        String country,
        @Schema(description = "The country's name, as it prints on a letter.")
        String countryName,
        Instant updatedAt
) {

    public static AddressResponse of(PostalAddress address, Instant updatedAt) {
        return new AddressResponse(address.line1(), address.line2(), address.city(), address.state(),
                address.postalCode(), address.country(), address.countryName(), updatedAt);
    }
}
