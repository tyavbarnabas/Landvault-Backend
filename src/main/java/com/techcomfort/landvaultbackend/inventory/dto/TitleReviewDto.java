package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@link EstateTitleDto} with what a HomeTrue reviewer needs to place it. */
@Schema(name = "TitleReviewDto")
public record TitleReviewDto(
        UUID id,
        UUID estateId,
        String titleType,
        String titleNumber,
        LocalDate issuedDate,
        String status,
        List<TitleDocumentDto> documents,
        @Schema(description = "Send this back with the decision: it names the submission you reviewed.")
        Instant submittedAt,
        UUID submittedBy,
        Instant decidedAt,
        UUID decidedBy,
        String verificationSource,
        String decisionNote,
        String estateName,
        String estateState,
        String companyName
) {

    public static TitleReviewDto of(EstateTitleDto t, String estateName, String estateState, String companyName) {
        return new TitleReviewDto(t.id(), t.estateId(), t.titleType(), t.titleNumber(), t.issuedDate(), t.status(),
                t.documents(), t.submittedAt(), t.submittedBy(), t.decidedAt(), t.decidedBy(),
                t.verificationSource(), t.decisionNote(), estateName, estateState, companyName);
    }
}
