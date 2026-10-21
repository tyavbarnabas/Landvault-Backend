package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /api/me/address} body: the buyer's whole address, replacing the
 * current one. For a Nigerian address the state must be one of the 36 states
 * or the FCT; elsewhere it's free text.
 */
public record SaveAddressRequest(
        @Schema(description = "House number and street.", example = "12 Adeola Odeku Street")
        @NotBlank @Size(max = 200) String line1,
        @Schema(description = "Optional: estate, area, flat.", example = "Victoria Island")
        @Size(max = 200) String line2,
        @Schema(example = "Lagos")
        @NotBlank @Size(max = 100) String city,
        @Schema(description = "For Nigeria, a state's name, code or common alias (\"Lagos State\", \"FCT\"); "
                + "stored as the canonical name. Free text for other countries.", example = "Lagos")
        @NotBlank @Size(max = 100) String state,
        @Schema(description = "Optional.", example = "101241")
        @Size(max = 20) String postalCode,
        @Schema(description = "Two-letter country code. May differ from the account's country of residence.",
                example = "NG")
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "Use a two-letter country code, e.g. NG.") String country
) {
}
