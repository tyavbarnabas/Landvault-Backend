package com.techcomfort.landvaultbackend.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which bucket a file lives in. The two never mix: a public file is served to
 * anyone through the CDN; a private one only through a link that expires in
 * minutes, issued by the module that decides who may see it.
 * On the wire {@code public} / {@code private}; either case is accepted.
 */
public enum StorageVisibility {
    PUBLIC("public"),
    PRIVATE("private");

    private final String value;

    StorageVisibility(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static StorageVisibility fromValue(String value) {
        for (StorageVisibility visibility : values()) {
            if (visibility.value.equalsIgnoreCase(value)) {
                return visibility;
            }
        }
        throw new IllegalArgumentException("Unknown visibility: " + value);
    }
}
