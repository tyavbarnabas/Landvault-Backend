package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * {@code POST /api/portal/estates/{id}/title} body. 1:1 with the estate.
 * <p>
 * {@code titleType} values carry spaces and an apostrophe ("C of O",
 * "Governor's Consent") — the established {@code @JsonValue} mapping handles
 * them; no case-conversion strategy would.
 */
public record CreateEstateTitleRequest(
        @NotBlank String titleType,
        String titleNumber,
        String issuedDate,
        UUID surveyPlanDocumentId,
        UUID deedDocumentId
) {
}
