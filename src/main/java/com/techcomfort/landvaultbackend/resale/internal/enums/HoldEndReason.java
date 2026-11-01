package com.techcomfort.landvaultbackend.resale.internal.enums;

/** How a new buyer's hold on a resale listing ended, from checkout's reservation outcome. */
public enum HoldEndReason {
    /** The buyer let it go, or finance rejected the payment. */
    RELEASED,
    /** The 45 minutes ran out, or the purchase was abandoned with nothing paid. */
    EXPIRED,
    /** The sale went through. */
    CONVERTED;

    public static HoldEndReason fromReservationOutcome(String outcome) {
        return switch (outcome) {
            case "released" -> RELEASED;
            case "converted" -> CONVERTED;
            default -> EXPIRED;
        };
    }
}
