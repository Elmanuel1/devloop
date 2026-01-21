package com.tosspaper.common.query;

import com.tosspaper.models.query.BaseQuery;
import org.jooq.Condition;
import org.jooq.TableField;
import org.jooq.Record;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class QueryConditionBuilder {
    public static <R extends Record> List<Condition> buildBaseConditions(
            BaseQuery query,
            TableField<R, String> statusField,
            TableField<R, OffsetDateTime> createdAtField,
            TableField<R, String> idField
    ) {
        List<Condition> conditions = new ArrayList<>();

        if (query.getStatus() != null && statusField != null) {
            conditions.add(statusField.eq(query.getStatus()));
        }
        
        // Date filters on created_at
        if (query.getCreatedDateFrom() != null && createdAtField != null) {
            conditions.add(createdAtField.ge(query.getCreatedDateFrom()));
        }
        if (query.getCreatedDateTo() != null && createdAtField != null) {
            conditions.add(createdAtField.le(query.getCreatedDateTo()));
        }

        // Cursor pagination: WHERE (created_at < cursor) OR (created_at = cursor AND id < cursor_id)
        if (query.getCursorCreatedAt() != null && query.getCursorId() != null && createdAtField != null && idField != null) {
            Condition cursorCondition = createdAtField.lessThan(query.getCursorCreatedAt())
                .or(createdAtField.eq(query.getCursorCreatedAt()).and(idField.lessThan(query.getCursorId())));
            conditions.add(cursorCondition);
        }
        
        return conditions;
    }

} 