package com.techcomfort.landvaultbackend.tenancy.dto;

import java.util.List;

/**
 * {@code additionalPermits} is always empty — no table backs the frontend's
 * "additional permits" concept distinct from a state regulator entry; see
 * AGENTS.md. Never fabricated, just genuinely empty until that exists.
 */
public record RegulatoryDto(
        String scumlNumber,
        List<StateRegulatorEntryDto> stateRegulators,
        String redanNumber,
        List<AdditionalPermitDto> additionalPermits
) {

    public record AdditionalPermitDto(String id, String name, String documentId) {
    }
}
