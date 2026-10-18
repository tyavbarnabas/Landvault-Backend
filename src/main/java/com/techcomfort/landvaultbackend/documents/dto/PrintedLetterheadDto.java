package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        What a letter issued under this letterhead would print today: the company's name and RC number always; \
        the branch's office details where it has them, otherwise head office; and each image and the signatory \
        from the branch where it sets its own, otherwise from the company. A branch's signatory is one unit — \
        their name is never printed beside the company signer's signature. Null where nothing is set.""")
public record PrintedLetterheadDto(
        String companyName,
        String rcNumber,
        String address,
        String phone,
        String email,
        String website,
        String signatoryName,
        String signatoryTitle,
        @Schema(description = "`company` or `branch`: whose signatory is printed.", example = "company")
        String signatorySource,
        LetterheadImageDto logo,
        LetterheadImageDto signature,
        LetterheadImageDto seal
) {
}
