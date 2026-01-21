package com.tosspaper.project;

import com.tosspaper.generated.model.Project;
import com.tosspaper.generated.model.ProjectCreate;
import com.tosspaper.generated.model.ProjectUpdate;
import com.tosspaper.models.jooq.tables.records.ProjectsRecord;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ProjectMapper {

    @Mapping(target = "status", qualifiedByName = "mapProjectUpdateStatusValue")
    Project toDto(ProjectsRecord record);

    List<Project> toDto(List<ProjectsRecord> records);

    @Mapping(target = "status", constant = "active")
    ProjectsRecord toRecord(Long companyId, ProjectCreate projectCreate);
    
    @Mapping(target = "status", source = "status.value")
    void updateRecord(ProjectUpdate projectUpdate, @MappingTarget ProjectsRecord record);

    @Named("mapProjectUpdateStatusValue")
    default Project.StatusEnum mapProjectUpdateStatusValue(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        return Project.StatusEnum.fromValue(status);
    }
} 