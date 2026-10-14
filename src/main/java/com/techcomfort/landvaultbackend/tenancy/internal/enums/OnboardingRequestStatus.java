package com.techcomfort.landvaultbackend.tenancy.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a company's onboarding request stands. {@code NEW} and
 * {@code CONTACTED} are open (one per email); {@code CONVERTED} (it became a
 * tenant) and {@code DECLINED} are final. Backend-defined: the frontend has no
 * union for it yet.
 */
public enum OnboardingRequestStatus {

    NEW("new"),
    CONTACTED("contacted"),
    CONVERTED("converted"),
    DECLINED("declined");

    private final String value;

    OnboardingRequestStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public boolean isOpen() {
        return this == NEW || this == CONTACTED;
    }

    @JsonCreator
    public static OnboardingRequestStatus fromValue(String value) {
        for (OnboardingRequestStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown onboarding request status: " + value);
    }
}
