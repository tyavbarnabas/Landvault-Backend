package com.techcomfort.landvaultbackend.tenancy;

/**
 * What a payout-account reviewer compares a new account against (TR-2): the
 * company's names, and the bank details it declared at onboarding. The
 * declared fields are null when none were captured. Read-only — nothing here
 * is ever paid to.
 */
public record CompanyBankProfile(String registeredName, String tradingName, String declaredBankName,
                                 String declaredAccountNumber, String declaredAccountName) {
}
