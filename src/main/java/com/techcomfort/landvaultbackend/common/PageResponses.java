package com.techcomfort.landvaultbackend.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.function.Function;

/**
 * The one place every paginated endpoint maps Spring's {@link Page} to
 * {@link PageResponse} — see that type's Javadoc for why the mapping has to
 * happen at all. {@code cursor} is the next page number as a string (page-
 * number paging, not a real opaque cursor yet — allowed for now per
 * AGENTS.md, since the frontend never parses it either way), {@code null}
 * once there's no next page.
 */
public final class PageResponses {

    private PageResponses() {
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return from(page, Function.identity());
    }

    public static <T, R> PageResponse<R> from(Page<T> page, Function<T, R> mapper) {
        List<R> items = page.getContent().stream().map(mapper).toList();
        String cursor = page.hasNext() ? String.valueOf(page.getNumber() + 1) : null;
        return new PageResponse<>(items, page.getTotalElements(), cursor, page.hasNext());
    }

    /**
     * Parses an incoming {@code cursor} (a page number, or absent for the
     * first page) and {@code limit} into a {@link org.springframework.data.domain.Pageable}.
     * A malformed cursor is treated as the first page rather than a 400 —
     * it's opaque to the frontend by contract, so it should never construct
     * one by hand, but failing soft here costs nothing and avoids a
     * confusing error for a stale/copy-pasted link.
     */
    public static Pageable pageable(String cursor, Integer limit) {
        int page = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                page = Math.max(0, Integer.parseInt(cursor));
            } catch (NumberFormatException ignored) {
                // See Javadoc — stays page 0.
            }
        }
        int size = (limit == null || limit <= 0) ? 20 : limit;
        return org.springframework.data.domain.PageRequest.of(page, size);
    }
}
