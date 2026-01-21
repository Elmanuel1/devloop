package com.tosspaper.project;

import com.tosspaper.models.jooq.tables.records.ProjectsRecord;
import com.tosspaper.project.model.ProjectQuery;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository {
    List<ProjectsRecord> find(Long companyId, ProjectQuery query);
    
    int count(Long companyId, ProjectQuery query);
    
    ProjectsRecord findById(String id);
    
    Optional<ProjectsRecord> findByKeyAndCompanyId(String key, Long companyId);
    
    ProjectsRecord create(ProjectsRecord ProjectsRecord);
    
    ProjectsRecord update(ProjectsRecord record);
} 