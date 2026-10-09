package com.techcomfort.landvaultbackend.payments.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Which verified sale to pay. The amount and the account are never in the request. */
public record SendPayoutRequest(@NotNull UUID transactionId) {
}
