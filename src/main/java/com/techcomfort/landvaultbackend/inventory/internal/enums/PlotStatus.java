package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a plot is in the sales pipeline. Wire values match the frontend's
 * {@code PlotStatus} union in {@code mockData.ts} exactly — hyphenated and
 * lowercase, which no case-conversion strategy produces, hence the explicit
 * {@link JsonValue}/{@link JsonCreator} pair (see AGENTS.md).
 * <p>
 * The two {@code AVAILABLE_*} constants are not redundant: an estate can sell
 * the same physical plot as a development plot (buyer builds) or an
 * investment plot (buyer holds), and the distinction drives which surface it
 * appears on.
 */
public enum PlotStatus {

    AVAILABLE_DEV("available-dev"),
    AVAILABLE_INV("available-inv"),
    RESERVED("reserved"),
    SOLD("sold"),
    /**
     * Off the market on the developer's decision — a survey dispute, a staff
     * allocation (IE-7). Never reserved by a buyer and never sold: those two
     * statuses are only ever reached through checkout. Returning a withheld
     * plot restores the available variant it had (see
     * {@code Plot.withheldFromStatus}).
     */
    WITHHELD("withheld");

    private final String value;

    PlotStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PlotStatus fromValue(String value) {
        for (PlotStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown PlotStatus: " + value);
    }
}
