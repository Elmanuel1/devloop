package com.tosspaper.models;

/**
 * Pagination metadata for cursor-based pagination
 *
 * @param nextCursor cursor for the next page (null if no more pages)
 * @param previousCursor cursor for the previous page (null if on first page)
 */
public record Pagination(
        String nextCursor,
        String previousCursor
) {
}

