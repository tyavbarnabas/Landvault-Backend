package com.techcomfort.landvaultbackend.kyc.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One required document and where it stands. {@code type} and {@code status}
 * are wire strings rather than the internal enums — a public DTO must not
 * expose an internal type through its own signature (AGENTS.md).
 */
@Schema(
        name = "KycDocument",
        description = """
                One document required of this buyer. The set is decided by country of residence, \
                not chosen by the buyer: a local buyer gets `nin` alone, a diaspora buyer gets \
                `passport` and `proof_of_address`.

                `status` is per-document on purpose — a rejection names the document that failed \
                and carries its own `rejectionReason`, so only that one is resubmitted.""")
public record KycDocumentDto(

        @Schema(description = "`nin`, `passport` or `proof_of_address`.", example = "nin")
        String type,

        @Schema(description = "`missing`, `submitted`, `approved` or `rejected`.", example = "submitted")
        String status,

        @Schema(description = "Null until the document is provided. Metadata only — file storage is "
                + "not built yet.", nullable = true)
        String fileName,

        @Schema(description = "Set only when this document was rejected, and only for that document.",
                nullable = true)
        String rejectionReason
) {
}
