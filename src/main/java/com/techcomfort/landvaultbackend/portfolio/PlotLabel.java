package com.techcomfort.landvaultbackend.portfolio;

/**
 * How a buyer's plot is named: the estate, and "Block A, Plot 7" (or "Plot 7"
 * in an estate without blocks). In the base package because {@code dto}
 * packages aren't exposed.
 */
public record PlotLabel(String estateName, String plotLabel) {
}
