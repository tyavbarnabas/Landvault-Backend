package com.techcomfort.landvaultbackend.common;

import java.util.List;

/**
 * The frontend's {@code Page<T>} envelope exactly (AGENTS.md's "Response
 * conventions to match", {@code pagination.ts}) — {@code items}/{@code total}/
 * {@code cursor}/{@code hasMore}, never Spring's own {@code Page} shape
 * ({@code content}/{@code totalElements}/{@code pageable}/...), which must
 * never be returned directly from a controller. Build via {@link PageResponses}
 * rather than this record's constructor directly, so every paginated
 * endpoint maps the same way.
 */
public record PageResponse<T>(List<T> items, long total, String cursor, boolean hasMore) {
}
