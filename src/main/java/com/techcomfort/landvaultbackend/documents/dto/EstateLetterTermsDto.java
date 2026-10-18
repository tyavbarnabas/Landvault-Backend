package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
        An estate's allocation conditions: the clauses the developer wrote (`clauses`), followed on the letter by \
        the ones HomeTrue writes from the estate's cost disclosure (`generatedClauses`) — fees, the refund \
        deduction, the development deadline, transfer consent, and that payments go only through HomeTrue. \
        The letter numbers them all in that order. HomeTrue reviews every version of the developer's clauses; \
        a letter uses the newest approved one (`approvedVersion`).""")
public record EstateLetterTermsDto(
        UUID estateId,
        String estateName,
        @Schema(description = "0 until the first save; each save is a new version, and earlier ones are kept.",
                example = "2")
        int version,
        @Schema(description = "The developer's own clauses, in order. Empty until the first save.")
        List<String> clauses,
        Instant updatedAt,
        @Schema(description = "Written by HomeTrue from the current cost disclosure; read-only. Change the "
                + "disclosure to change these.")
        List<String> generatedClauses,
        DisclosureVersionsDto disclosureVersions,
        @Schema(description = "Disclosure parts not declared, so missing from the letter: `fees`, "
                + "`refund_terms`, `default_terms`. Empty when complete.", example = "[\"default_terms\"]")
        List<String> missingDisclosure,
        @Schema(description = """
                HomeTrue's review of the current version: `pending`, `approved` or `rejected`; null before the \
                first save. Every version is reviewed before a letter may use it.""", example = "pending")
        String reviewStatus,
        @Schema(description = "HomeTrue's note on the current version — always present on a rejection, saying what to change.")
        String reviewNote,
        Instant reviewedAt,
        @Schema(description = """
                The version letters use: the newest one HomeTrue approved. While a newer version awaits review, \
                letters keep using this one. Null when none has been approved, and the estate can't issue \
                letters yet.""", example = "1")
        Integer approvedVersion
) {

    @Schema(description = "The versions of the cost disclosure `generatedClauses` were written from; null where none is declared.")
    public record DisclosureVersionsDto(Integer fees, Integer refundTerms, Integer defaultTerms) {
    }
}
