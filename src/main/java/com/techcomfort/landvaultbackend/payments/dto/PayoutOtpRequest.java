package com.techcomfort.landvaultbackend.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The code Paystack sent the account owner. Passed straight to Paystack; never stored or logged. */
public record PayoutOtpRequest(@NotBlank @Pattern(regexp = "\\d{4,8}", message = "must be the digits Paystack sent") String otp) {

    /** Never prints the code, so it can't reach a log by accident. */
    @Override
    public String toString() {
        return "PayoutOtpRequest[otp=***]";
    }
}
