package com.tosspaper.common;

import lombok.experimental.UtilityClass;

@UtilityClass
public class HeaderUtils {

    /**
     * Safely parses the X-Context-Id header value to a Long company ID.
     * 
     * @param xContextId the X-Context-Id header value
     * @return the parsed company ID as Long
     * @throws BadRequestException if the header value is not a valid Long
     */
    public static Long parseCompanyId(String xContextId) {
        try {
            return Long.parseLong(xContextId);
        } catch (NumberFormatException e) {
            throw new BadRequestException(
                ApiErrorMessages.INVALID_HEADER_FORMAT, 
                ApiErrorMessages.INVALID_CONTEXT_ID_FORMAT.formatted(xContextId)
            );
        }
    }
} 