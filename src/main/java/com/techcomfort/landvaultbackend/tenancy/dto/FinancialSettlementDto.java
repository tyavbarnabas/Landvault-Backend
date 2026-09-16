package com.techcomfort.landvaultbackend.tenancy.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.util.Map;

/**
 * {@code gateways} maps gateway name (wire value, e.g. {@code "Paystack"})
 * to status (wire value, e.g. {@code "connected"}) — matches the frontend's
 * {@code Partial<Record<GatewayName, GatewayStatus>>}. No credentials here
 * by design — see {@code OrganizationGateway} and AGENTS.md.
 */
public record FinancialSettlementDto(
        String bankName,
        String accountNumber,
        String accountName,
        Currency settlementCurrency,
        Map<String, String> gateways
) {
}
