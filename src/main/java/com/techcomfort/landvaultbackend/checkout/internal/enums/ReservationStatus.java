package com.techcomfort.landvaultbackend.checkout.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The life of a hold. Wire values are the frontend's
 * {@code Reservation.status} union verbatim.
 * <p>
 * The task spec called the cancelled state {@code CANCELLED}; the frontend's
 * contract, which already exists, says {@code released}. The contract wins,
 * so the constant is named for the wire value rather than leaving the two
 * spellings to drift apart.
 */
public enum ReservationStatus {

    /** Holding the plot. Exactly one of these can exist per plot. */
    ACTIVE("active"),

    /** The 45 minutes ran out and the sweeper returned the plot. */
    EXPIRED("expired"),

    /** The buyer (or the platform) gave it up early. */
    RELEASED("released"),

    /**
     * Payment cleared and the plot was allocated. Nothing in this module
     * sets it: allocation happens after finance verification, which does
     * not exist yet (TX-3).
     */
    CONVERTED("converted");

    private final String value;

    ReservationStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ReservationStatus fromValue(String value) {
        for (ReservationStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown ReservationStatus: " + value);
    }
}
