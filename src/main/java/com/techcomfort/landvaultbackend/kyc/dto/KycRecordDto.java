package com.techcomfort.landvaultbackend.kyc.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A buyer's own verification state.
 * <p>
 * <strong>The submitted NIN is deliberately not a field here</strong>, even
 * though the frontend's {@code KycRecord} types it as optional. It is
 * NDPR-regulated data that nothing in this system reads back, so returning
 * it — even masked — would widen exposure for no purpose. Absence is
 * compatible with the frontend's optional field.
 */
@Schema(
        name = "KycRecord",
        description = """
                Purchase-time identity verification for the signed-in buyer.

                **A buyer with no record at all reads as `unsubmitted`** with every required document \
                `missing`. That is an honest absence, not a defaulted row: nothing is written until \
                the buyer actually submits.

                Verification is held at platform level, so it is done once and works with every \
                company on the marketplace — a buyer is never re-verified per developer.

                The submitted NIN is never returned, by any route.""")
public record KycRecordDto(

        @Schema(description = "`local` (resident in Nigeria) or `diaspora`. Derived from the country "
                + "captured at registration, never asked again.", example = "local")
        String buyerType,

        @Schema(description = "`unsubmitted`, `submitted`, `under_review`, `approved` or `rejected`.",
                example = "approved")
        String status,

        @Schema(description = "Every document required of this buyer, including ones not yet provided.")
        List<KycDocumentDto> documents,

        @Schema(nullable = true) Instant submittedAt,

        @Schema(description = "When a reviewer approved or rejected. Null while nobody has decided.",
                nullable = true)
        Instant decidedAt
) {
}
