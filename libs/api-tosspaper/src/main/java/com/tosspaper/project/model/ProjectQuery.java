package com.tosspaper.project.model;

import com.tosspaper.models.query.BaseQuery;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class ProjectQuery extends BaseQuery {
    // Project-specific query parameters can be added here if needed
    // For now, inherits cursor, direction, limit from BaseQuery
}
