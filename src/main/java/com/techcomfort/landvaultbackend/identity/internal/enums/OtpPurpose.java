package com.techcomfort.landvaultbackend.identity.internal.enums;

/**
 * What an {@code otp_codes} row is for. One table serves every OTP purpose
 * rather than one table per purpose — see AGENTS.md.
 * <p>
 * Deliberately has exactly one constant: a value with no code path behind it
 * would be the same fabrication as a permission slug nothing checks. Add
 * {@code REGISTRATION} here (and to the table's CHECK constraint) only in the
 * slice that actually builds registration verification.
 */
public enum OtpPurpose {

    PASSWORD_RESET
}
