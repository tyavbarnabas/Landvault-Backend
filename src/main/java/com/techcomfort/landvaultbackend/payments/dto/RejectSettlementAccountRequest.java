package com.techcomfort.landvaultbackend.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a payout account was refused — the company sees it. */
public record RejectSettlementAccountRequest(@NotBlank @Size(max = 500) String reason) {
}
