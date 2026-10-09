package com.techcomfort.landvaultbackend.payments.dto;

/** A bank Paystack can pay to. {@code code} is what a payout account is submitted with. */
public record BankDto(String code, String name) {
}
