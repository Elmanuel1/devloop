package com.tosspaper.models.paging;

public record Pagination(
        int page,

         int pageSize,

         int totalPages,

         int totalItems) {
} 