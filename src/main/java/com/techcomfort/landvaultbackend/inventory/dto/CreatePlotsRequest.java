package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /api/portal/estates/{id}/plots} body — a batch, because a real
 * estate has hundreds of plots and one request each would be unusable.
 * <p>
 * Capped so a single request can't be used to push an unbounded write; a
 * larger estate is created in several batches.
 */
public record CreatePlotsRequest(
        @NotEmpty @Size(max = 500) @Valid List<CreatePlotRequest> plots
) {
}
