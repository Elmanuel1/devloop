package com.tosspaper.common.query;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedResult<T> {
    private List<T> data;
    private int total;
    private int page;
    private int pageSize;
}

