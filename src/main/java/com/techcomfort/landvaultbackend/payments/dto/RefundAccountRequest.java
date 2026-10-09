package com.techcomfort.landvaultbackend.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The buyer's bank account for a refund. No name: the bank supplies it. */
public record RefundAccountRequest(
        @NotBlank String bankCode,
        @NotBlank @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number") String accountNumber) {
}
