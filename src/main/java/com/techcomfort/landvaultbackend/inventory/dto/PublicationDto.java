package com.techcomfort.landvaultbackend.inventory.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The result of publishing or unpublishing an estate.
 * <p>
 * {@code warningConflictCount}/{@code warning} carry MEDIUM conflicts: one
 * company's own boundaries overlapping, which doesn't refuse publication but
 * which the developer should be told about. Always 0/null on unpublish.
 */
public record PublicationDto(
        UUID estateId,
        boolean published,
        Instant publishedAt,
        int warningConflictCount,
        String warning
) {
}
