package com.techcomfort.landvaultbackend.common;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(
        name = "Page",
        description = """
                The pagination envelope used by every list endpoint. **`cursor` is opaque**: pass \
                it back as the `cursor` parameter to get the next page, and never parse it. When \
                `hasMore` is false, `cursor` is null.""")
public record PageResponse<T>(List<T> items, long total, String cursor, boolean hasMore) {
}
