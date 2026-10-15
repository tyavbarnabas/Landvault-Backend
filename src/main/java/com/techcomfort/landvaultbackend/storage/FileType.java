package com.techcomfort.landvaultbackend.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The file types storage accepts, each recognised by its first bytes — what a
 * file claims to be is never trusted. Deliberately no SVG or HTML: both can
 * carry script. On the wire {@code jpeg}, {@code png}, {@code webp}, {@code pdf};
 * either case is accepted.
 */
public enum FileType {
    JPEG("image/jpeg", ".jpg"),
    PNG("image/png", ".png"),
    WEBP("image/webp", ".webp"),
    PDF("application/pdf", ".pdf");

    /** How many leading bytes {@link #matches} needs to look at. */
    public static final int SIGNATURE_BYTES = 12;

    private final String mimeType;
    private final String extension;

    FileType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static FileType fromValue(String value) {
        for (FileType type : values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown file type: " + value);
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<FileType> fromMimeType(String mimeType) {
        if (mimeType == null) {
            return Optional.empty();
        }
        String bare = mimeType.split(";", 2)[0].trim();
        return Arrays.stream(values()).filter(t -> t.mimeType.equalsIgnoreCase(bare)).findFirst();
    }

    /** Whether these leading bytes really are this type. */
    public boolean matches(byte[] head) {
        return switch (this) {
            case JPEG -> startsWith(head, 0, 0xFF, 0xD8, 0xFF);
            case PNG -> startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case WEBP -> startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P');
            case PDF -> startsWith(head, 0, '%', 'P', 'D', 'F', '-');
        };
    }

    private static boolean startsWith(byte[] head, int offset, int... expected) {
        if (head == null || head.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
