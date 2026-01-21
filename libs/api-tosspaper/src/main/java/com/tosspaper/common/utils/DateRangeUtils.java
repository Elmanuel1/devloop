package com.tosspaper.common.utils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * A utility class for handling date and time conversions, specifically for creating time ranges.
 */
public final class DateRangeUtils {

    private DateRangeUtils() {
        // Prevent instantiation
    }

    /**
     * Returns an OffsetDateTime representing the start of the day (00:00:00) for the given date in UTC.
     *
     * @param date the local date
     * @return the OffsetDateTime at the start of the day in UTC, or null if the input is null.
     */
    public static OffsetDateTime toStartOfDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /**
     * Returns an OffsetDateTime representing the end of the day (23:59:59.999999999) for the given date in UTC.
     *
     * @param date the local date
     * @return the OffsetDateTime at the end of the day in UTC, or null if the input is null.
     */
    public static OffsetDateTime toEndOfDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.atTime(LocalTime.MAX).atOffset(ZoneOffset.UTC);
    }
} 