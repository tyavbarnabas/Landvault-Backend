package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One problem found in an uploaded plot file. */
public record PlotImportIssueDto(
        @Schema(description = "1-based position of the feature in the file; null for a problem with the file as a whole.")
        Integer feature,
        @Schema(description = "The plot number the feature carries, when it has one.")
        String plotNumber,
        String code,
        String message
) {
}
