package com.tosspaper.project;


import com.tosspaper.generated.model.Project;
import com.tosspaper.generated.model.ProjectCreate;
import com.tosspaper.generated.model.ProjectUpdate;
import com.tosspaper.generated.model.ProjectList;
import com.tosspaper.common.ForbiddenException;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.project.model.ProjectQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMapper projectMapper;

    @Override
    public ProjectList getProjects(Long companyId, ProjectQuery query) {
        // Fetch projects for the requested page
        var projectRecords = projectRepository.find(companyId, query);
        
        // Get total count for pagination metadata
        int totalItems = projectRepository.count(companyId, query);
        int totalPages = (int) Math.ceil((double) totalItems / query.getPageSize());
        
        List<Project> projects = projectMapper.toDto(projectRecords);
        
        var paginationDto = new com.tosspaper.generated.model.Pagination()
                .page(query.getPage())
                .pageSize(query.getPageSize())
                .totalPages(totalPages)
                .totalItems(totalItems);
        
        var result = new ProjectList();
        result.setData(projects);
        result.setPagination(paginationDto);
        
        log.debug("{} Projects retrieved for company {} (page {}/{}, total: {})", 
                projects.size(), companyId, query.getPage(), totalPages, totalItems);
        return result;
    }

    @Override
    public Project createProject(Long companyId, ProjectCreate projectCreate) {
        var record = projectMapper.toRecord(companyId, projectCreate);
        var createdRecord = projectRepository.create(record);
        log.debug("Project {} created or updated", createdRecord.getId());
        return projectMapper.toDto(createdRecord);
    }

    @Override
    public Project getProjectById(Long companyId, String projectId) {
        var project = projectRepository.findById(projectId);
        if (!project.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.ACCESS_DENIED_TO_PROJECT);
        }
        log.debug("Project {} retrieved", project.getId());
        return projectMapper.toDto(project);
    }

    @Override
    public void updateProject(Long companyId, String projectId, ProjectUpdate projectUpdate) {
        var record = projectRepository.findById(projectId);
        if (!record.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.ACCESS_DENIED_TO_PROJECT);
        }

        // Check if updates are allowed for this project status
        validateStatusTransition(record.getStatus(), projectUpdate.getStatus() != null ? projectUpdate.getStatus().getValue() : record.getStatus());

        projectMapper.updateRecord(projectUpdate, record);
        projectRepository.update(record);
        log.debug("Project {} updated with {}", record.getId(), projectUpdate);
    }
    
    /**
     * Validates project updates and status transitions:
     * - Projects in COMPLETED/CANCELLED states cannot be updated at all
     * - From ACTIVE: can transition to any status
     * - From ARCHIVED: can only transition to ACTIVE
     */
    private void validateStatusTransition(String currentStatus, String newStatus) {
        com.tosspaper.project.ProjectStatus current = com.tosspaper.project.ProjectStatus.fromValue(currentStatus);
        com.tosspaper.project.ProjectStatus target = com.tosspaper.project.ProjectStatus.fromValue(newStatus);
        
        if (current == null || target == null) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.PROJECT_STATUS_INVALID);
        }
        
        switch (current) {
            case COMPLETED, CANCELLED:
                // Projects in final states cannot be updated at all
                throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, String.format(
                    ApiErrorMessages.PROJECT_UPDATE_NOT_ALLOWED_FINAL_STATE, 
                    current.getValue()));
                    
            case ACTIVE:
                // From ACTIVE: can transition to any status
                // Same status = no change, always allowed
                break;
                
            case ARCHIVED:
                // Same status = no change, always allowed
                if (currentStatus.equals(newStatus)) {
                    return;
                }
                // From ARCHIVED: can only transition to ACTIVE
                if (target != com.tosspaper.project.ProjectStatus.ACTIVE) {
                    throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, String.format(
                        ApiErrorMessages.PROJECT_STATUS_TRANSITION_FROM_ARCHIVED, 
                        current.getValue(), target.getValue()));
                }
                break;
        }
    }
} 