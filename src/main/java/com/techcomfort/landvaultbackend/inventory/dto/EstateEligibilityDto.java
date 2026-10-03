package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why this estate can or cannot be listed, condition by condition.
 * <p>
 * Assembled in {@code inventory} rather than taken straight from
 * {@code marketplace}'s {@code EstateEligibility}, because
 * {@code noBlockingConflict} comes from a third module: the eligibility view
 * folds the conflict check into {@code eligible} alone, and
 * {@code marketplace} has no dependency on {@code conflicts} to expose it
 * separately.
 */
@Schema(
        name = "EstateEligibility",
        description = """
                Each publication condition on its own, so a developer can see what is outstanding \
                **without attempting a publish and reading it off an exception**.

                Every boolean is a fact the server computed. None is inferred, and an unknown \
                condition is never reported as met.

                `eligible` is the conjunction of all of them. It exists alongside the individual \
                flags rather than instead of them: a single boolean can say "no" but not "why", \
                which is the whole reason these are kept apart.""")
public record EstateEligibilityDto(

        @Schema(description = "The developer's own opt-in switch.")
        boolean published,

        @Schema(description = "The owning company has cleared verification.")
        boolean tenantVerified,

        @Schema(description = "The company's plan includes marketplace publishing.")
        boolean tenantEntitled,

        @Schema(description = "The company's portal access is active — not suspended or offboarded.")
        boolean tenantActive,

        @Schema(description = "A fee schedule has been declared. Declaring an empty one counts; "
                + "saying nothing does not.")
        boolean feesDeclared,

        @Schema(description = "Refund terms have been declared.")
        boolean refundTermsDeclared,

        @Schema(description = "The estate has a boundary. Without one it cannot be checked for "
                + "overlaps with other companies' land, so it cannot be listed. Applies to every "
                + "estate, including ones published before this rule.")
        boolean hasBoundary,

        /**
         * Explicit rather than inferable. Without it a client has to reason
         * "every named condition passes but {@code eligible} is false, so it
         * must be a conflict" — which is correct today and breaks silently
         * the moment an eighth condition is added and not exposed, telling a
         * developer the wrong reason.
         */
        @Schema(description = "No boundary conflict is blocking this estate. A HIGH-severity "
                + "overlap with another company blocks; a same-company overlap only warns.")
        boolean noBlockingConflict,

        @Schema(description = "All of the above. What the public feed actually filters on.")
        boolean eligible
) {
}
