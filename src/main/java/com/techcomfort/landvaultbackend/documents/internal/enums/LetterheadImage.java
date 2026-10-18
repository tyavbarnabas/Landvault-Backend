package com.techcomfort.landvaultbackend.documents.internal.enums;

import java.util.Arrays;
import java.util.Optional;

/** The three images a letterhead carries. Each is its own storage purpose, so a file says what it was uploaded as. */
public enum LetterheadImage {
    LOGO("logo"),
    SIGNATURE("signature"),
    SEAL("seal");

    private final String value;

    LetterheadImage(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public String storagePurpose() {
        return "letterhead-" + value;
    }

    public static Optional<LetterheadImage> fromValue(String value) {
        return Arrays.stream(values()).filter(k -> k.value.equalsIgnoreCase(value == null ? "" : value.strip()))
                .findFirst();
    }

    public static Optional<LetterheadImage> fromStoragePurpose(String purpose) {
        return Arrays.stream(values()).filter(k -> k.storagePurpose().equals(purpose)).findFirst();
    }
}
