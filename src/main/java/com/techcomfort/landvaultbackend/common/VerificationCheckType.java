package com.techcomfort.landvaultbackend.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which due-diligence check an {@code EstateVerificationCheck} row records.
 * Independent of the title instrument itself ({@link TitleType}) — an estate
 * can hold a valid C of O and still sit on land with an encroachment notice.
 */
public enum VerificationCheckType {

    AGIS_REGISTRATION("agis_registration"),
    ENCROACHMENT_STATUS("encroachment_status"),
    TITLE_VERIFICATION("title_verification");

    private final String value;

    VerificationCheckType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static VerificationCheckType fromValue(String value) {
        for (VerificationCheckType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown VerificationCheckType: " + value);
    }
}
