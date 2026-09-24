package com.techcomfort.landvaultbackend.kyc.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which document set a buyer must satisfy, derived from their country of
 * residence — captured once at registration and never asked again (KY-2).
 * <p>
 * Stored on the record rather than recomputed on read, for the same reason
 * {@code directors.is_beneficial_owner} is stored: it says what this buyer
 * was actually required to produce at the moment they submitted, and a later
 * change of residence must not retroactively rewrite that.
 */
public enum KycBuyerType {

    /** Resident in Nigeria: NIN only — never a utility bill. */
    LOCAL("local"),

    /** Resident anywhere else: passport plus proof of address. */
    DIASPORA("diaspora");

    private final String value;

    KycBuyerType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static KycBuyerType fromValue(String value) {
        for (KycBuyerType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown KycBuyerType: " + value);
    }

    /**
     * The one place residence maps to a document set. {@code NG} is local,
     * everything else is diaspora — matching {@code buyerTypeForCountry} in
     * the frontend's {@code kycService.ts} exactly, and the local/diaspora
     * split {@code AuthService} already reports in the login response.
     */
    public static KycBuyerType forCountry(String countryCode) {
        return "NG".equalsIgnoreCase(countryCode) ? LOCAL : DIASPORA;
    }
}
