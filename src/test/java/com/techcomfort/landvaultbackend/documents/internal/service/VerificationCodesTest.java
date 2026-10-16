package com.techcomfort.landvaultbackend.documents.internal.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationCodesTest {

    @Test
    void codesAreSixteenHexCharactersAndDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = VerificationCodes.generate();
            assertThat(code).matches("[0-9A-F]{16}");
            assertThat(seen.add(code)).isTrue();
        }
    }

    @Test
    void whatAPersonTypesIsNormalisedAndAnythingElseIsNotACode() {
        assertThat(VerificationCodes.normalise(" 3f9a-1c77-b02e-55d1 ")).isEqualTo("3F9A1C77B02E55D1");
        assertThat(VerificationCodes.display("3F9A1C77B02E55D1")).isEqualTo("3F9A-1C77-B02E-55D1");
        assertThat(VerificationCodes.normalise("3F9A-1C77")).isNull();
        assertThat(VerificationCodes.normalise("ZZZZ-1C77-B02E-55D1")).isNull();
        assertThat(VerificationCodes.normalise("' OR 1=1 --")).isNull();
        assertThat(VerificationCodes.normalise(null)).isNull();
    }

    @Test
    void theVerifyPageShowsInitialsOnly() {
        assertThat(DocumentService.initials("Tyav  Barnabas sughter")).isEqualTo("T. B. S.");
        assertThat(ReceiptIssuer.number(Instant.parse("2026-12-31T23:30:00Z"), 42))
                .as("the year is Nigeria's: 00:30 on 1 January in Lagos").isEqualTo("HT-RCT-2027-000042");
    }
}
