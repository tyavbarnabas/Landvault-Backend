package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * An estate's current fee declaration. {@code declaredAt} null means nobody
 * has declared anything — which is not the same as declaring no fees, and is
 * what blocks publication.
 */
@Schema(
        name = "FeeSchedule",
        description = """
                The current declaration, with its version.

                **An empty `fees` list with a non-null `declaredAt` means "no extra charges", and \
                permits publication.** A null `declaredAt` means nobody has said anything, and \
                blocks it. The distinction is the point: silence is not a disclosure.

                `version` increments on every declaration; earlier versions are retained so a \
                buyer's acknowledgement can name what they were shown.""")
public record FeeScheduleDto(
        int version,
        @Schema(nullable = true) Instant declaredAt,
        List<FeeDto> fees
) {
}
