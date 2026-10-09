package com.techcomfort.landvaultbackend.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A payout account: the bank and the 10-digit NUBAN, nothing else. The
 * account name is deliberately absent — LandVault asks the bank for it.
 */
public record SubmitSettlementAccountRequest(
        @NotBlank String bankCode,
        @NotBlank @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number") String accountNumber) {
}
