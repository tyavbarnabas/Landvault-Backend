package com.techcomfort.landvaultbackend.kyc.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code POST /api/kyc}. Which fields are required depends on the buyer's
 * own country, not on what they send — a local buyer must supply an NIN, a
 * diaspora buyer a passport and proof of address, and the service rejects a
 * submission that doesn't satisfy its own buyer type.
 * <p>
 * Files arrive as <strong>metadata</strong>, not bytes. Object storage isn't
 * built (same position as estate boundary upload), so this records where a
 * file would live rather than pretending to have received one.
 */
@Schema(
        name = "SubmitKycRequest",
        description = """
                Submit identity documents. The required set is derived from the buyer's country of \
                residence — supplying a passport as a local buyer does not substitute for the NIN.

                **Files are metadata only.** Object storage is not built yet, so this endpoint \
                records a file name and an optional storage key; it does not receive bytes. A \
                multipart upload route is follow-up work, not a gap being hidden here.

                The NIN is encrypted at rest and is never returned by any route.""")
public record SubmitKycRequest(

        @Schema(description = "Required for a local (Nigerian-resident) buyer, ignored for a diaspora "
                + "buyer. Encrypted at rest; never read back.", example = "12345678901")
        String ninNumber,

        @Schema(description = "Metadata for the NIN slip. Required for a local buyer.")
        KycFileRef ninFile,

        @Schema(description = "Metadata for the passport bio page. Required for a diaspora buyer.")
        KycFileRef passportFile,

        @Schema(description = "Metadata for proof of address. Required for a diaspora buyer.")
        KycFileRef proofOfAddressFile
) {

    /** What a real upload would leave behind once storage exists. */
    @Schema(name = "KycFileRef", description = "A file's metadata. No bytes are transferred.")
    public record KycFileRef(
            @Schema(example = "nin-slip.pdf") String fileName,
            @Schema(example = "184213") Long fileSize,
            @Schema(description = "Where the object lives, once object storage exists.", nullable = true)
            String storageKey
    ) {
    }
}
