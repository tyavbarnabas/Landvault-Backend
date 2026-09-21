package com.techcomfort.landvaultbackend.inventory.dto;

import java.time.LocalDate;
import java.util.UUID;

public record EstateTitleDto(
        UUID id,
        UUID estateId,
        String titleType,
        String titleNumber,
        LocalDate issuedDate,
        UUID surveyPlanDocumentId,
        UUID deedDocumentId
) {
}
