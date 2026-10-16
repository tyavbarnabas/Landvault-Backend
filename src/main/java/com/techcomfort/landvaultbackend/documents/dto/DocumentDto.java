package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One document in a buyer's vault — the frontend's {@code Document}, plus what a real one needs. */
@Schema(name = "Document")
public record DocumentDto(
        UUID id,
        @Schema(description = "receipt (offer_letter and allocation_letter later)") String type,
        @Schema(description = "e.g. `Receipt HT-RCT-2026-000123 — Block A, Plot 7, Double King Deluxe Estate`")
        String title,
        @Schema(description = "The printed number, e.g. `HT-RCT-2026-000123`.") String number,
        @Schema(description = "The day it was issued, in Nigerian time.") LocalDate date,
        Instant issuedAt,
        @Schema(description = """
                `valid`. (`void` and `superseded` are for reissued letters.) A refunded payment's receipt stays \
                valid — the money did arrive — and carries `refundedAt`.""") String status,
        @Schema(description = "The verification code as printed, e.g. `3F9A-1C77-B02E-55D1`.") String qrCode,
        @Schema(description = "What the QR code opens: the public verify page for this document.") String verifyUrl,
        @Schema(description = """
                Null: documents are drawn on demand, so a file size isn't known until it's downloaded.""",
                nullable = true) String size,
        @Schema(description = "The inventory plot the document is about (not a portfolio id).") UUID plotId,
        UUID transactionId,
        @Schema(nullable = true) UUID supersedes,
        @Schema(description = "When the payment was returned to the buyer; null if it wasn't.", nullable = true)
        Instant refundedAt,
        @Schema(description = "Download the PDF (the official copy).") String pdfUrl,
        @Schema(description = "Download the same page as a JPEG, for sharing.") String jpegUrl
) {
}
