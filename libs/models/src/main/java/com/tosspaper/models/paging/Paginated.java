package com.tosspaper.models.paging;

import java.util.List;

/**
 * A generic wrapper for a paginated list of results using offset-based pagination.
 *
 * @param data The list of data for the current page.
 * @param pagination The pagination object containing offset-based pagination info (page, pageSize, totalPages, totalItems).
 * @param <T> The type of the data records.
 */
public record Paginated<T>(List<T> data, Pagination pagination) {
} 