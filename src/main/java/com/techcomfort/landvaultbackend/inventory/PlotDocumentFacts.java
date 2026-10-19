package com.techcomfort.landvaultbackend.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A plot as a generated letter names it. In the base package for the same
 * reason as {@link EstateDocumentFacts}: {@code dto} packages aren't exposed.
 *
 * @param blockName      null in an estate without blocks
 * @param nominalSizeSqm what it was sold as; null for a unit with no land area of its own
 */
public record PlotDocumentFacts(UUID plotId, UUID estateId, String blockName, String plotNumber,
                                BigDecimal nominalSizeSqm, boolean corner) {

    /** "Block A, Plot 7", or just "Plot 7" in an estate without blocks. */
    public String label() {
        return blockName == null || blockName.isBlank() ? "Plot " + plotNumber
                : "Block " + blockName + ", Plot " + plotNumber;
    }
}
