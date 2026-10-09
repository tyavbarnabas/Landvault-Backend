package com.techcomfort.landvaultbackend.payments.dto;

import java.util.UUID;

/**
 * A submission as a Super Admin reviews it (TR-2). Two WARNINGS, never
 * blocks: {@code nameMatchesCompany} compares the bank's name with the
 * company's registered and trading names; {@code matchesOnboardingAccount}
 * compares with the account declared at onboarding (null when none was).
 * {@code currentApproved} is what payouts use today, if anything.
 */
public record SettlementAccountReviewDto(UUID tenantId, String registeredName, String tradingName,
                                         SettlementAccountDto account, boolean nameMatchesCompany,
                                         String onboardingBankName, String onboardingAccountNumber,
                                         String onboardingAccountName, Boolean matchesOnboardingAccount,
                                         SettlementAccountDto currentApproved) {
}
