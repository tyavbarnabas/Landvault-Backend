package com.techcomfort.landvaultbackend.documents.internal.render;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFormatsTest {

    @Test
    void anRcNumberPrintsWithOnePrefixHoweverItWasEntered() {
        assertThat(DocumentFormats.rcNumber("1234567")).isEqualTo("RC 1234567");
        assertThat(DocumentFormats.rcNumber("RC-1234567")).isEqualTo("RC 1234567");
        assertThat(DocumentFormats.rcNumber("RC 1234567")).isEqualTo("RC 1234567");
        assertThat(DocumentFormats.rcNumber("rc1234567")).isEqualTo("RC 1234567");
        assertThat(DocumentFormats.rcNumber(" ")).isNull();
        assertThat(DocumentFormats.rcNumber(null)).isNull();
    }
}
