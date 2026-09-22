package com.techcomfort.landvaultbackend.conflicts.internal.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Detection tuning. One knob today: the sliver threshold.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "landvault.conflicts")
public class ConflictProperties {

    /**
     * Minimum shared area, in square metres, below which no conflict is
     * recorded.
     * <p>
     * <strong>Why a threshold exists at all.</strong> Two correctly
     * surveyed adjacent plots meet along a shared boundary. In exact
     * arithmetic that intersection is a line with zero area, but real
     * survey coordinates carry precision limits and floating-point geometry
     * produces a sliver. Without this, every correctly-surveyed neighbour
     * in a subdivided estate raises a conflict and the review queue becomes
     * noise nobody reads — which is worse than no queue, because it hides
     * the real ones.
     * <p>
     * <strong>Why 1.0.</strong> On a typical 250 m² plot (about 15.8 m a
     * side), 1 m² is a strip roughly 6 cm wide along one edge — comfortably
     * within survey tolerance, and far below any overlap that could
     * represent a real claim on the same ground. A genuine double
     * allocation overlaps by a meaningful fraction of a plot, orders of
     * magnitude above this. Raise it if real survey data proves noisier;
     * do not raise it to silence conflicts that are actually real.
     */
    private BigDecimal minOverlapSqm = new BigDecimal("1.0");
}
