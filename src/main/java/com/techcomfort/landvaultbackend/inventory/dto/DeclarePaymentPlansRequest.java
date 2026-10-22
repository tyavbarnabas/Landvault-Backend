package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code PUT /api/portal/estates/{id}/payment-plans}: the estate's instalment
 * plans, replacing the current set as a new version. An empty list means
 * outright only.
 */
@Schema(
        name = "DeclarePaymentPlansRequest",
        description = """
                Declare the instalment plans this estate offers. HomeTrue works out the deposit, each \
                instalment and the total from these terms for every price tier — no amount a buyer pays is \
                typed in here. **The premium applies to the whole price; a percentage deposit is a share of \
                that total.** Buyers already on a plan keep the version they chose.""")
public record DeclarePaymentPlansRequest(
        @NotNull @Valid @Size(max = 10) List<Plan> plans
) {

    @Schema(name = "PaymentPlanDeclaration", description = "Exactly one of depositPct and depositAmount.")
    public record Plan(
            @NotBlank @Size(max = 80) @Schema(example = "12-month plan") String name,
            @DecimalMin("1.00") @DecimalMax("99.99")
            @Schema(nullable = true, example = "30.00", description = "A share of the plan total.")
            BigDecimal depositPct,
            @Positive @Schema(nullable = true, description = "A fixed deposit, in the tier's currency.")
            BigDecimal depositAmount,
            @NotNull @Min(1) @Max(60) @Schema(example = "12", description = "Instalments after the deposit.")
            Integer instalmentCount,
            @NotBlank @Schema(example = "monthly", description = "`monthly` or `quarterly`.") String frequency,
            @DecimalMin("0.00") @DecimalMax("100.00")
            @Schema(nullable = true, example = "10.00", description = "How much more than paying in full; "
                    + "none when left out.")
            BigDecimal premiumPct
    ) {
    }
}
