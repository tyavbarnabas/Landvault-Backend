package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Everything found in an uploaded plot file, in one report (FU-2) — one
 * answer for a 450-plot file rather than 450 separate rejections. Returned
 * by the preview, by a successful import, and (with HTTP 422) by an import
 * refused because the file has errors.
 */
public record PlotImportReportDto(
        @Schema(description = "Features in the file.")
        int featureCount,
        @Schema(description = "Features with no errors.")
        int importableCount,
        @Schema(description = "True when the file has no errors. Warnings never stop an import.")
        boolean canImport,
        @Schema(description = "True only on a completed import.")
        boolean imported,
        @Schema(description = "Plots created; 0 unless imported.")
        int createdCount,
        @Schema(description = "Block names in the file that don't exist on the estate yet and will be created.")
        List<String> blocksToCreate,
        @Schema(description = "How many plots each tier receives, by tier label.")
        Map<String, Integer> plotsPerTier,
        @Schema(description = "Problems that stop the import. Fix the file and upload again.")
        List<PlotImportIssueDto> errors,
        @Schema(description = "Worth knowing but not blocking — e.g. two plots in the file overlap. Overlaps "
                + "are recorded for review after import, exactly as for plots added one at a time.")
        List<PlotImportIssueDto> warnings,
        @Schema(description = "After an import: overlapping plot pairs in the whole estate. Null on a preview.")
        Integer plotOverlapsInEstate,
        String note
) {
}
