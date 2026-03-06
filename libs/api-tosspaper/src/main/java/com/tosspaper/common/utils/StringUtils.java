package com.tosspaper.common.utils;

import lombok.experimental.UtilityClass;

/** General-purpose string utilities shared across all modules. */
@UtilityClass
public class StringUtils {

    /**
     * Returns {@code true} when {@code value} is {@code null} or blank.
     *
     * @param value the string to test
     * @return {@code true} if null or blank; {@code false} otherwise
     */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
