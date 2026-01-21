package com.tosspaper.project;

import com.tosspaper.generated.model.Project;
import com.tosspaper.generated.model.ProjectCreate;
import com.tosspaper.generated.model.ProjectUpdate;
import com.tosspaper.generated.model.ProjectList;
import com.tosspaper.project.model.ProjectQuery;

public interface ProjectService {
    ProjectList getProjects(Long companyId, ProjectQuery query);
    
    Project createProject(Long companyId, ProjectCreate projectCreate);
    
    Project getProjectById( Long companyId, String projectId);
    
    void updateProject( Long companyId, String projectId, ProjectUpdate projectUpdate);
} 