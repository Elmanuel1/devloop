package com.tosspaper.common;

import java.util.List;

public final class PaginationUtils {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private PaginationUtils() {}

    public static int clampLimit(Integer limit) {
        if (limit == null || limit < MIN_LIMIT || limit > MAX_LIMIT) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, MIN_LIMIT, MAX_LIMIT);
    }

    public static <T> boolean hasMore(List<T> records, int effectiveLimit) {
        return records.size() > effectiveLimit;
    }

    public static <T> List<T> truncate(List<T> records, int effectiveLimit) {
        return records.size() > effectiveLimit ? records.subList(0, effectiveLimit) : records;
    }
}
