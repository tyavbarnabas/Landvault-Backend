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

    @Schema(name = "PaymentPlanDeclaration", description = """
            An instalment plan: exactly one of depositPct and depositAmount, plus instalmentCount and frequency. A \
            flexible plan (`kind: "flexible"`): depositPct is the minimum to start (at least 10), and `flexible` \
            carries its terms; depositAmount, instalmentCount and frequency are refused.""")
    public record Plan(
            @NotBlank @Size(max = 80) @Schema(example = "12-month plan") String name,
            @Schema(nullable = true, example = "installment", description = "`installment` (when left out) or "
                    + "`flexible`.") String kind,
            @DecimalMin("1.00") @DecimalMax("99.99")
            @Schema(nullable = true, example = "30.00", description = "A share of the plan total: the deposit, or on a "
                    + "flexible plan the minimum to start.")
            BigDecimal depositPct,
            @Positive @Schema(nullable = true, description = "A fixed deposit, in the tier's currency. Instalment "
                    + "plans only.")
            BigDecimal depositAmount,
            @Min(1) @Max(60) @Schema(nullable = true, example = "12", description = "Instalments after the deposit. "
                    + "Instalment plans only.")
            Integer instalmentCount,
            @Schema(nullable = true, example = "monthly", description = "`monthly` or `quarterly`. Instalment plans "
                    + "only.") String frequency,
            @DecimalMin("0.00") @DecimalMax("100.00")
            @Schema(nullable = true, example = "10.00", description = "How much more than paying in full; "
                    + "none when left out.")
            BigDecimal premiumPct,
            @Valid @Schema(nullable = true, description = "A flexible plan's terms; required with `kind: flexible`, "
                    + "refused otherwise.") Flexible flexible
    ) {
    }

    @Schema(name = "FlexiblePlanDeclaration")
    public record Flexible(
            @Positive @Schema(nullable = true, example = "100000", description = "The smallest later payment, in the "
                    + "tier's currency; the one that finishes the plan may be smaller. None when left out.")
            BigDecimal minimumContribution,
            @NotNull @Valid @Schema(description = "What the developer keeps when the buyer leaves.") Exit exit,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Schema(example = "40.00", description = """
                    The share of any gain you keep when HomeTrue next sells a plot a buyer left, for more than that \
                    buyer's price; the buyer gets the rest. Required: 100 keeps it all, 0 gives it all to the buyer.""")
            BigDecimal developerGainSharePct,
            @NotNull @Min(6) @Max(60) @Schema(example = "6", description = "Months with no verified payment before "
                    + "you may give notice. At least 6.") Integer inactivityMonths,
            @NotNull @Min(30) @Max(365) @Schema(example = "30", description = "The notice before you may end a "
                    + "dormant plan. At least 30 days.") Integer noticeDays,
            @Min(12) @Max(240) @Schema(nullable = true, example = "36", description = "Months from the offer date to "
                    + "finish; none when left out. At least 12.") Integer maxMonths
    ) {
    }

    /** As on refund terms: a share of each payment, or a fixed sum on the agreed price. */
    @Schema(name = "FlexiblePlanExitDeclaration")
    public record Exit(
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Schema(example = "15.00") BigDecimal deductionPct,
            @NotBlank @Schema(example = "amount_paid", description = "`amount_paid` (a share of each payment) or "
                    + "`total_price` (a fixed sum, that share of the agreed price, kept from the first payments).")
            String appliesTo
    ) {
    }
}
