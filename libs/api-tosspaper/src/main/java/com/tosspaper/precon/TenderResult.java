package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.Tender;

/**
 * Wraps a Tender DTO with its version (for ETag).
 * The version is not exposed in the API response body;
 * it's only used to build the ETag header.
 */
public record TenderResult(Tender tender, int version) {}
