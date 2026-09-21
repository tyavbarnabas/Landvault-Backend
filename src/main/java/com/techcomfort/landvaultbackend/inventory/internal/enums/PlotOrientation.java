package com.techcomfort.landvaultbackend.inventory.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which way a plot faces — the frontend's {@code Plot.orientation} union. */
public enum PlotOrientation {

    N("N"), S("S"), E("E"), W("W"),
    NE("NE"), NW("NW"), SE("SE"), SW("SW");

    private final String value;

    PlotOrientation(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PlotOrientation fromValue(String value) {
        for (PlotOrientation orientation : values()) {
            if (orientation.value.equalsIgnoreCase(value)) {
                return orientation;
            }
        }
        throw new IllegalArgumentException("Unknown PlotOrientation: " + value);
    }
}
