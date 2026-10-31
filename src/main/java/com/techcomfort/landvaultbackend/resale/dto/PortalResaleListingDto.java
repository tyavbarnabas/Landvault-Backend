package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sell my plot, phase 4: a resale listing on one of the developer's estates,
 * as the developer sees it. The seller is already the developer's customer,
 * so their name and purchase reference are shown; what they paid is not.
 */
@Schema(name = "PortalResaleListing")
public record PortalResaleListingDto(
        UUID id,
        @Schema(description = "`awaiting_consent` (yours to answer), `listed` (on the marketplace), `withdrawn` (the "
                + "seller took it down), `expired` (90 days with no buyer), `refused` (you refused) or `ended` (a rule "
                + "took it down — `closeReason`).") String status,
        UUID estateId,
        @Schema(nullable = true) String estateName,
        UUID plotId,
        @Schema(nullable = true, description = "\"Block A, Plot 7\", or \"Plot 7\" in an estate without blocks.")
        String plotLabel,
        @Schema(nullable = true) String sellerName,
        String transactionReference,
        @Schema(description = "How the seller bought: `paid_in_full`, `installment` or `flexible`.") String kind,
        @Schema(description = "Before the seller's profit thresholds: the price is your current price and any gain "
                + "above what they agreed goes to you.") boolean earlySale,
        @Schema(nullable = true, description = "The price buyers see (an early sale's is your current price; null "
                + "once an early sale has come down).") BigDecimal price,
        String currency,
        @Schema(description = "The estate's terms needed your consent when it was listed.") boolean requiresConsent,
        Instant requestedAt,
        @Schema(nullable = true, description = "You have until then to answer; with no answer the request lapses.")
        Instant consentDueAt,
        @Schema(nullable = true) Instant consentDecidedAt,
        @Schema(nullable = true) String refusalReason,
        @Schema(nullable = true, description = "When it went on the marketplace.") Instant listedAt,
        @Schema(nullable = true) Instant expiresAt,
        @Schema(nullable = true) Instant closedAt,
        @Schema(nullable = true, description = "Why a rule took it down: `consent_not_given`, `in_arrears`, "
                + "`notice_running`, `estate_not_listed`, `early_sale_below_agreed_price` or `plan_ended`.")
        String closeReason
) {
}
