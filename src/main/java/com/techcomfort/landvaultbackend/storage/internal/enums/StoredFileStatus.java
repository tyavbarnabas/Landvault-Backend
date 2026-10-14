package com.techcomfort.landvaultbackend.storage.internal.enums;

/**
 * PENDING: an upload link was issued, nothing confirmed. READY: arrived and
 * checked. REJECTED: what arrived wasn't what was promised, and was deleted.
 * EXPIRED: never confirmed in time, and cleaned up. DELETED: removed on purpose.
 */
public enum StoredFileStatus {
    PENDING("pending"),
    READY("ready"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    DELETED("deleted");

    private final String value;

    StoredFileStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
