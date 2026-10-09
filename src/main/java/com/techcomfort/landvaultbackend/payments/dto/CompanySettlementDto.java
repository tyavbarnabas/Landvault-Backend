package com.techcomfort.landvaultbackend.payments.dto;

import java.util.List;

/** A company's payout accounts: the one paid to (or null), the one waiting (or null), and all history newest first. */
public record CompanySettlementDto(SettlementAccountDto approved, SettlementAccountDto pending,
                                   List<SettlementAccountDto> history) {
}
