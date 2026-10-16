package com.techcomfort.landvaultbackend.inventory.internal.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Which piece of title evidence a file is. The wire value is also the storage
 * purpose, so a confirmed file says what it was uploaded as.
 */
public enum TitleDocumentKind {

    /** The C of O, R of O, consent or Gazette page itself. */
    TITLE_DOCUMENT("title_document", "title-document"),
    SURVEY_PLAN("survey_plan", "survey-plan"),
    DEED("deed", "title-deed");

    private final String value;
    private final String storagePurpose;

    TitleDocumentKind(String value, String storagePurpose) {
        this.value = value;
        this.storagePurpose = storagePurpose;
    }

    public String getValue() {
        return value;
    }

    public String storagePurpose() {
        return storagePurpose;
    }

    /** @throws IllegalArgumentException an unknown kind */
    public static TitleDocumentKind fromValue(String value) {
        return Arrays.stream(values()).filter(k -> k.value.equals(value == null ? null : value.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown document kind: " + value + ". Use title_document, survey_plan or deed."));
    }

    public static Optional<TitleDocumentKind> fromStoragePurpose(String purpose) {
        return Arrays.stream(values()).filter(k -> k.storagePurpose.equals(purpose)).findFirst();
    }
}
