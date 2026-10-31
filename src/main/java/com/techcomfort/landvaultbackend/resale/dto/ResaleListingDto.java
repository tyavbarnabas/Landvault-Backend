package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Sell my plot, phase 2: one of the seller's listings. */
@Schema(name = "ResaleListing")
public record ResaleListingDto(
        UUID id,
        UUID transactionId,
        String transactionReference,
        @Schema(description = "`paid_in_full`, `installment` or `flexible`.") String kind,
        @Schema(description = "`awaiting_consent` (the estate needs the developer's consent; not on the marketplace "
                + "yet), `listed`, `withdrawn` (you took it down), `expired` (90 days with no buyer), `refused` (the "
                + "developer refused — `refusalReason`) or `ended` (a rule took it down — `closeReason`).")
        String status,
        @Schema(description = "An early sale: its price is always the developer's current one.") boolean earlySale,
        @Schema(description = "The price buyers see: your asking price, or on an early sale the developer's current "
                + "price (null when the estate has none to show, and once an early sale has come down — it has no "
                + "price of its own).", nullable = true) BigDecimal price,
        String currency,
        @Schema(nullable = true, description = "When it went on the marketplace; null while it waits for consent.")
        Instant listedAt,
        @Schema(nullable = true, description = "90 days after `listedAt`: it comes down by itself then if no buyer has "
                + "taken it.") Instant expiresAt,
        @Schema(nullable = true) Instant closedAt,
        @Schema(nullable = true, description = "Why a rule took it down: `in_arrears`, `notice_running`, "
                + "`estate_not_listed`, `early_sale_below_agreed_price`, `plan_ended` or `consent_not_given` (the "
                + "developer didn't answer in time).") String closeReason,
        @Schema(nullable = true) String closeMessage,
        @Schema(description = "On a flexible plan, this (first) listing stops the inactivity clock while it is up.")
        boolean pausesInactivity,
        @Schema(nullable = true, description = "While open: the split at `price`, as the eligibility read gives it.")
        ResaleEligibilityDto.Quote quote,
        @Schema(description = "The estate's terms needed the developer's consent when you listed.")
        boolean requiresConsent,
        @Schema(nullable = true, description = "The developer has until then to answer.") Instant consentDueAt,
        @Schema(nullable = true) Instant consentDecidedAt,
        @Schema(nullable = true, description = "The developer's reason for refusing.") String refusalReason
) {
}
