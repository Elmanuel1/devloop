package com.tosspaper.project;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.ProjectsApi;
import com.tosspaper.generated.model.Project;
import com.tosspaper.generated.model.ProjectCreate;
import com.tosspaper.generated.model.ProjectUpdate;
import com.tosspaper.generated.model.ProjectList;
import com.tosspaper.project.model.ProjectQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class ProjectController implements ProjectsApi {

    private final ProjectService projectService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'projects:create')")
    public ResponseEntity<Project> createProject(String xContextId, ProjectCreate projectCreate) {
        log.info("POST /v1/projects - Creating project: name={}, key={}, defaultShippingLocation={}", 
                projectCreate.getName(), projectCreate.getKey(), projectCreate.getDefaultShippingLocation());
        var project = projectService.createProject(HeaderUtils.parseCompanyId(xContextId), projectCreate);
        return ResponseEntity.status(HttpStatus.CREATED).body(project);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'projects:view')")
    public ResponseEntity<Project> getProjectById(String xContextId, String id) {
        log.debug("GET /v1/projects/{} - Fetching project", id);
        Project project = projectService.getProjectById(HeaderUtils.parseCompanyId(xContextId), id);
        return ResponseEntity.ok(project);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'projects:view')")
    public ResponseEntity<ProjectList> getProjects(String xContextId, Integer page, Integer pageSize, String status, String search, OffsetDateTime createdDateFrom, OffsetDateTime createdDateTo) {
        log.debug("GET /v1/projects - Listing projects: page={}, pageSize={}, status={}", page, pageSize, status);
        
        var query = ProjectQuery.builder()
                .page(page != null ? page : 1)
                .pageSize(pageSize != null ? pageSize : 20)
                .search(search)
                .status(status)
                .createdDateFrom(createdDateFrom)
                .createdDateTo(createdDateTo)
                .build();
        var projectList = projectService.getProjects(HeaderUtils.parseCompanyId(xContextId), query);
        return ResponseEntity.ok(projectList);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'projects:edit')")
    public ResponseEntity<Void> updateProject(String xContextId, String id, ProjectUpdate projectUpdate) {
        log.info("PUT /v1/projects/{} - Updating project: name={}, status={}, defaultShippingLocation={}", 
                id, projectUpdate.getName(), projectUpdate.getStatus(), projectUpdate.getDefaultShippingLocation());
        projectService.updateProject(HeaderUtils.parseCompanyId(xContextId), id, projectUpdate);
        return ResponseEntity.noContent().build();
    }
} 