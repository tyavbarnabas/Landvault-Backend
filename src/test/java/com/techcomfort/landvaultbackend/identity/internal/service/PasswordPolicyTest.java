package com.techcomfort.landvaultbackend.identity.internal.service;

import org.junit.jupiter.api.Test;

import static com.techcomfort.landvaultbackend.identity.internal.service.PasswordPolicy.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule, case by case. Mirrors the frontend's src/lib/passwordPolicy.ts —
 * if one of these changes, that file must change the same way.
 */
class PasswordPolicyTest {

    private static String check(String password) {
        return problemWith(password, "ada.obi@example.com", "Ada", "Obi");
    }

    @Test
    void anOrdinaryGoodPasswordPasses() {
        assertThat(check("harmattan7")).isNull();
        assertThat(check("Kano-rains-2026")).isNull();
    }

    @Test
    void lengthIsCheckedInCharactersAndInBytes() {
        assertThat(check("har7mat")).isEqualTo(TOO_SHORT);
        assertThat(check("a1" + "x".repeat(63))).as("65 characters").isEqualTo(TOO_LONG);
        assertThat(check("a1" + "x".repeat(62))).as("64 characters is fine").isNull();
        assertThat(check("é".repeat(40) + "1a")).as("42 characters but 82 bytes").isEqualTo(TOO_MANY_BYTES);
        assertThat(check("é".repeat(30) + "1a")).as("62 bytes: within BCrypt's 72").isNull();
    }

    @Test
    void lettersAndANumberAreRequired() {
        assertThat(check("correct horse battery staple")).isEqualTo(LETTERS_AND_NUMBER);
        assertThat(check("20261231")).isEqualTo(LETTERS_AND_NUMBER);
        assertThat(check("éééééééé1")).as("letters means a-z/A-Z, as on the frontend").isEqualTo(LETTERS_AND_NUMBER);
    }

    @Test
    void commonPasswordsAreRefusedWithTheirDecorationsStripped() {
        assertThat(check("password123!")).isEqualTo(TOO_COMMON);
        assertThat(check("123Lagos!!9")).as("core is 'lagos'").isEqualTo(TOO_COMMON);
        assertThat(check("passw0rd")).as("matched whole").isEqualTo(TOO_COMMON);
        assertThat(check("1234harmattan")).isEqualTo(TOO_COMMON);
        assertThat(check("ABCD2026x")).isEqualTo(TOO_COMMON);
        assertThat(check("0123harmattan")).isEqualTo(TOO_COMMON);
    }

    @Test
    void theUsersOwnNameOrEmailIsRefusedOnlyWhenThreeCharactersOrMore() {
        assertThat(check("ada.obi2026")).isEqualTo(PERSONAL);
        assertThat(check("harmattanADA7")).as("case-insensitive").isEqualTo(PERSONAL);
        assertThat(problemWith("harmattan7jo", "x@example.com", "Jo", "Li")).as("two-letter names don't count").isNull();
        assertThat(problemWith("harmattan7", null, null, null)).isNull();
    }

    @Test
    void theFirstFailingRuleIsReported() {
        assertThat(check("ada")).isEqualTo(TOO_SHORT);
        assertThat(check("adaobiada")).as("no number comes before personal").isEqualTo(LETTERS_AND_NUMBER);
    }
}
