package com.techcomfort.landvaultbackend.inventory.internal.enums;

/** Lifecycle of an estate boundary correction. Persisted by name; the wire value is lowercase. */
public enum BoundaryChangeStatus {
    /** Small, or the estate wasn't published: took effect at once. */
    APPLIED,
    /** A published estate's land changing past the review threshold: waits for a Super Admin. */
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN;

    public String wire() {
        return name().toLowerCase();
    }
}
