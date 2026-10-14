package com.techcomfort.landvaultbackend.tenancy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code POST /api/onboarding-requests}: a developer company asking to be onboarded. Public; grants nothing. */
public record OnboardingRequestSubmission(
        @Schema(example = "Estintin Group Ltd")
        @NotBlank @Size(max = 200) String companyName,
        @Schema(description = "CAC RC number. Optional.", example = "RC1234567")
        @Size(max = 20) String rcNumber,
        @Schema(description = "Office address. Optional.", example = "12 Adeola Odeku Street, Victoria Island, Lagos")
        @Size(max = 500) String companyAddress,
        @Schema(description = "Nigerian states the company sells land in — name, ISO code or common spelling. Optional.",
                example = "[\"Lagos\", \"FCT\"]")
        @Size(max = 37) List<String> statesOfOperation,
        @Schema(description = "Roughly how many estates the company has. Optional.", example = "4")
        @PositiveOrZero @Max(10_000) Integer estimatedEstateCount,
        @Schema(example = "Adaeze Okafor")
        @NotBlank @Size(max = 150) String contactName,
        @Schema(description = "The contact's role in the company. Optional.", example = "Managing Director")
        @Size(max = 100) String contactRole,
        @Schema(example = "adaeze@estintin.com")
        @NotBlank @Email @Size(max = 254) String contactEmail,
        @Schema(example = "+234 803 123 4567")
        @NotBlank @Pattern(regexp = "^\\+?[0-9 ()-]{7,30}$", message = "must be a phone number") String contactPhone,
        @Schema(description = "`email`, `phone`, `whatsapp` or `meeting`.", example = "phone")
        @NotBlank String preferredContactMethod,
        @Schema(description = "When suits them, in their own words. Optional.", example = "Weekdays after 2pm")
        @Size(max = 200) String preferredContactTime,
        @Schema(description = "Anything else they want to tell HomeTrue. Optional.")
        @Size(max = 2000) String message
) {
}
