package com.tosspaper.project;

import static com.tosspaper.models.jooq.Tables.PROJECTS;
import static org.jooq.impl.DSL.condition;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.exception.NoDataFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.DuplicateException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.common.query.QueryConditionBuilder;
import com.tosspaper.models.jooq.tables.records.ProjectsRecord;
import com.tosspaper.project.model.ProjectQuery;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProjectRepositoryImpl implements ProjectRepository {

    private final DSLContext dsl;

    @Override
    public List<ProjectsRecord> find(Long companyId, ProjectQuery query) {
        int offset = (query.getPage() - 1) * query.getPageSize();
        
        var selectQuery = dsl.selectFrom(PROJECTS)
                .where(buildConditions(companyId, query))
                .orderBy(PROJECTS.CREATED_AT.desc())
                .limit(query.getPageSize())
                .offset(offset);
        
        return selectQuery.fetch();
    }

    @Override
    public int count(Long companyId, ProjectQuery query) {
        return dsl.selectCount()
                .from(PROJECTS)
                .where(buildConditions(companyId, query))
                .fetchOne(0, int.class);
    }

    private List<org.jooq.Condition> buildConditions(Long companyId, ProjectQuery query) {
        var conditions = new java.util.ArrayList<org.jooq.Condition>();
        conditions.add(PROJECTS.COMPANY_ID.eq(companyId));
        conditions.addAll(QueryConditionBuilder.buildBaseConditions(query, PROJECTS.STATUS, PROJECTS.CREATED_AT, PROJECTS.ID));
        
        // Add full-text search condition using tsvector
        if (query.getSearch() != null && !query.getSearch().trim().isEmpty()) {
            String prefixQuery = com.tosspaper.models.utils.PostgresSearchUtils.buildPrefixQuery(query.getSearch());
            
            if (!prefixQuery.isEmpty()) {
                conditions.add(condition("search_vector @@ to_tsquery('english', ?)", prefixQuery));
            }
        }
        
        return conditions;
    }

    @Override
    public ProjectsRecord findById(String id) {
        return dsl.selectFrom(PROJECTS)
                .where(PROJECTS.ID.eq(id))
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException(ApiErrorMessages.PROJECT_NOT_FOUND_CODE, ApiErrorMessages.PROJECT_NOT_FOUND));
    }

    @Override
    public Optional<ProjectsRecord> findByKeyAndCompanyId(String key, Long companyId) {
        return dsl.selectFrom(PROJECTS)
                .where(PROJECTS.KEY.eq(key))
                .and(PROJECTS.COMPANY_ID.eq(companyId))
                .fetchOptional();
    }

    @Override
    public ProjectsRecord create(ProjectsRecord projectsRecord) {
        try {
            return dsl.insertInto(PROJECTS)
                    .set(projectsRecord)
                    .returning()
                    .fetchOne();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(
                    "api.project.duplicate",
                    ApiErrorMessages.PROJECT_ALREADY_EXISTS.formatted(projectsRecord.getKey()));
        }
    }

    @Override
    public ProjectsRecord update(ProjectsRecord record) {
        try {
            record.setUpdatedAt(OffsetDateTime.now());
            return dsl.update(PROJECTS)
                    .set(record)
                    .where(PROJECTS.ID.eq(record.getId()))
                    .returning()
                    .fetchSingle();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(
                    "api.project.duplicate",
                    ApiErrorMessages.PROJECT_ALREADY_EXISTS.formatted(record.getKey()));
        } catch (NoDataFoundException e) {
            throw new NotFoundException(ApiErrorMessages.PROJECT_NOT_FOUND_CODE, ApiErrorMessages.PROJECT_NOT_FOUND);
        }
    }
}