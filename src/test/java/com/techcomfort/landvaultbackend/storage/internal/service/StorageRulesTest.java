package com.techcomfort.landvaultbackend.storage.internal.service;

import com.techcomfort.landvaultbackend.storage.FileType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** File types are recognised by their bytes; a person's file name is kept only as a harmless label. */
class StorageRulesTest {

    @Test
    void eachTypeIsRecognisedByItsFirstBytesAndNothingElse() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] webp = "RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1);
        byte[] pdf = "%PDF-1.7\n\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] html = "<html><body>".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(FileType.JPEG.matches(jpeg)).isTrue();
        assertThat(FileType.PNG.matches(png)).isTrue();
        assertThat(FileType.WEBP.matches(webp)).isTrue();
        assertThat(FileType.PDF.matches(pdf)).isTrue();
        assertThat(FileType.JPEG.matches(png)).isFalse();
        assertThat(FileType.WEBP.matches("RIFF\0\0\0\0WAVE".getBytes(StandardCharsets.ISO_8859_1)))
                .as("a RIFF file that isn't WebP").isFalse();
        for (FileType type : FileType.values()) {
            assertThat(type.matches(html)).isFalse();
            assertThat(type.matches(new byte[2])).as("too short").isFalse();
        }
    }

    @Test
    void mimeTypesAreMatchedLooselyButNeverInvented() {
        assertThat(FileType.fromMimeType("IMAGE/JPEG; charset=binary")).contains(FileType.JPEG);
        assertThat(FileType.fromMimeType("image/svg+xml")).isEmpty();
        assertThat(FileType.fromMimeType(null)).isEmpty();
    }

    @Test
    void aFileNameLosesItsPathAndAnythingThatCouldBreakAHeader() {
        assertThat(StorageService.safeFileName("C:\\Users\\ada\\site plan.jpg", FileType.JPEG)).isEqualTo("site plan.jpg");
        assertThat(StorageService.safeFileName("../../etc/passwd", FileType.PDF)).isEqualTo("passwd");
        assertThat(StorageService.safeFileName("a\"b\r\nc.png", FileType.PNG)).isEqualTo("abc.png");
        assertThat(StorageService.safeFileName("  ", FileType.WEBP)).isEqualTo("file.webp");
        assertThat(StorageService.safeFileName(null, FileType.JPEG)).isEqualTo("file.jpg");
        assertThat(StorageService.safeFileName("x".repeat(300), FileType.JPEG)).hasSize(255);
    }
}
