package com.techcomfort.landvaultbackend.inventory.dto;

import java.util.Map;

/**
 * How many plots an estate has, and how they break down by status.
 * <p>
 * {@code byStatus} is keyed by the status wire value ({@code "available-dev"},
 * {@code "sold"}, …) and <strong>only carries statuses that actually occur</strong>
 * — a status with no plots is absent, not zero. An estate with no plots at
 * all gets {@code total: 0} and an empty map, never a fabricated spread. A
 * caller rendering a fixed set of status chips supplies its own zero.
 * <p>
 * A map rather than a field per status on purpose: adding a {@code PlotStatus}
 * constant then needs no change here, and no chance of a new status silently
 * going uncounted.
 */
public record PlotCountsDto(long total, Map<String, Long> byStatus) {

    public static PlotCountsDto empty() {
        return new PlotCountsDto(0L, Map.of());
    }
}
