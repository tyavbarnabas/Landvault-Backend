package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Waive some or all of the penalty still owed on one unpaid instalment. The reason is emailed to the buyer. */
@Schema(name = "WaivePenaltyRequest")
public record WaivePenaltyRequest(
        @NotNull @Min(1) @Schema(example = "1") Integer instalmentSequence,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2)
        @Schema(description = "At most the instalment's `outstanding` penalty.", example = "48125.00")
        BigDecimal amount,
        @NotBlank @Size(max = 500) @Schema(example = "The buyer was in hospital in September.") String reason
) {
}
