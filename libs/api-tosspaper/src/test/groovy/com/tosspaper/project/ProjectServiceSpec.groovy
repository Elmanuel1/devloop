package com.tosspaper.project


import com.tosspaper.common.ForbiddenException
import com.tosspaper.generated.model.ProjectCreate
import com.tosspaper.generated.model.ProjectUpdate
import com.tosspaper.models.jooq.tables.records.ProjectsRecord
import org.mapstruct.factory.Mappers
import spock.lang.Specification
import spock.lang.Subject

class ProjectServiceSpec extends Specification {

    private ProjectRepository projectRepository
    private ProjectMapper projectMapper = Mappers.getMapper(ProjectMapper.class)

    @Subject
    private ProjectService projectService

    def setup() {
        projectRepository = Mock()
        projectService = new ProjectServiceImpl(projectRepository, projectMapper)
    }

    def "should get project by id"() {
        given: "a project id and company id"
        def projectId = UUID.randomUUID().toString()
        def companyId = 1L

        and: "a project record"
        def projectRecord = new ProjectsRecord(
                id: projectId,
                companyId: companyId,
                key: "KEY",
                name: "name",
                status: ProjectStatus.ACTIVE.getValue()
        )

        and: "repository is mocked"
        projectRepository.findById(projectId) >> projectRecord

        when: "service is called"
        def result = projectService.getProjectById(companyId, projectId)

        then: "result is returned with correct values"
        with(result) {
            id == projectRecord.id
            key == "KEY"
            name == "name"
            status.value == com.tosspaper.generated.model.ProjectStatus.ACTIVE.value
        }
    }

    def "get project by id should throw forbidden exception for wrong company"() {
        given: "a project id and company id"
        def projectId = UUID.randomUUID().toString()
        def companyId = 1L
        def wrongCompanyId = 2L

        and: "a project record with a different company id"
        def projectRecord = new ProjectsRecord(
                id: projectId,
                companyId: wrongCompanyId,
                key: "KEY",
                name: "name",
                status: ProjectStatus.ACTIVE.getValue()
        )

        and: "repository is mocked"
        projectRepository.findById(projectId) >> projectRecord

        when: "service is called with the wrong company id"
        projectService.getProjectById(companyId, projectId)

        then: "a forbidden exception is thrown"
        thrown(ForbiddenException)
    }

    def "should create a project"() {
        given: "a company id and create request"
        def companyId = 1L
        def createRequest = new ProjectCreate(key: "NEWKEY", name: "New Project", description: "Project Description")

        and: "repository finds no existing project with the same key"
        projectRepository.findByKeyAndCompanyId(createRequest.key, companyId) >> Optional.empty()

        and: "repository create is mocked"
        def createdRecord = new ProjectsRecord(id: UUID.randomUUID().toString(), companyId: companyId, key: createRequest.getKey(), name: createRequest.getName(), description: createRequest.getDescription(), status: ProjectStatus.ACTIVE.getValue())
        projectRepository.create(_ as ProjectsRecord) >> createdRecord

        when: "the project is created"
        def result = projectService.createProject(companyId, createRequest)

        then: "the created project is returned and has correct values"
        result.id == createdRecord.id
        result.key == createRequest.key
        result.name == createRequest.name
        result.status.value == createdRecord.status
        result.description == createRequest.description

    }

    def "should update a project"() {
        given: "a company id, project id, and update request"
        def companyId = 1L
        def projectId = UUID.randomUUID().toString()
        def updateRequest = new ProjectUpdate(name: "Updated Name", status: ProjectUpdate.StatusEnum.ARCHIVED)

        and: "a project record"
        def projectRecord = new ProjectsRecord(id: projectId, companyId: companyId, name:"Old Name", status: ProjectStatus.ACTIVE.getValue())
        projectRepository.findById(projectId) >> projectRecord

        when: "the project is updated"
        projectService.updateProject(companyId, projectId, updateRequest)

        then: "the repository's update method is called with the updated record"
        1 * projectRepository.update({ ProjectsRecord record ->
            record.name == "Updated Name"
        })
    }

    def "should throw forbidden exception when updating a project with wrong company id"() {
        given: "a company id, project id, and update request"
        def companyId = 1L
        def wrongCompanyId = 2L
        def projectId = UUID.randomUUID().toString()
        def updateRequest = new ProjectUpdate(name: "Updated Name")

        and: "a project record with a different company id"
        def projectRecord = new ProjectsRecord(id: projectId, companyId: wrongCompanyId, status: ProjectStatus.ACTIVE.getValue())
        projectRepository.findById(projectId) >> projectRecord

        when: "updating the project"
        projectService.updateProject(companyId, projectId, updateRequest)

        then: "a forbidden exception is thrown"
        thrown(ForbiddenException)
    }
    

    
    def "should allow valid status transitions from ACTIVE"(ProjectUpdate.StatusEnum targetStatus) {
        given: "a company id, project id, and update request"
        def companyId = 1L
        def projectId = UUID.randomUUID().toString()
        def updateRequest = new ProjectUpdate(status: targetStatus)

        and: "an ACTIVE project record"
        def projectRecord = new ProjectsRecord(id: projectId, companyId: companyId, status: "active")
        projectRepository.findById(projectId) >> projectRecord

        when: "the project status is updated"
        projectService.updateProject(companyId, projectId, updateRequest)

        then: "the repository's update method is called successfully"
        1 * projectRepository.update({ ProjectsRecord record ->
            record.status == targetStatus.getValue()
        })

        where:
        targetStatus << [
            ProjectUpdate.StatusEnum.ARCHIVED,
            ProjectUpdate.StatusEnum.COMPLETED,
            ProjectUpdate.StatusEnum.CANCELLED
        ]
    }
    
    def "should allow transition from ARCHIVED to ACTIVE"() {
        given: "a company id, project id, and update request to reactivate"
        def companyId = 1L
        def projectId = UUID.randomUUID().toString()
        def updateRequest = new ProjectUpdate(status: ProjectUpdate.StatusEnum.ACTIVE)

        and: "an ARCHIVED project record"
        def projectRecord = new ProjectsRecord(id: projectId, companyId: companyId, status: "archived")
        projectRepository.findById(projectId) >> projectRecord

        when: "the project is reactivated"
        projectService.updateProject(companyId, projectId, updateRequest)

        then: "the repository's update method is called successfully"
        1 * projectRepository.update({ ProjectsRecord record ->
            record.status == "active"
        })
    }
    
    def "should prevent invalid transitions from ARCHIVED"(ProjectUpdate.StatusEnum targetStatus) {
        given: "a company id, project id, and update request"
        def companyId = 1L
        def projectId = UUID.randomUUID().toString()
        def updateRequest = new ProjectUpdate(status: targetStatus)

        and: "an ARCHIVED project record"
        def projectRecord = new ProjectsRecord(id: projectId, companyId: companyId, status: "archived")
        projectRepository.findById(projectId) >> projectRecord

        when: "an invalid status transition is attempted"
        projectService.updateProject(companyId, projectId, updateRequest)

        then: "a forbidden exception is thrown"
        def e = thrown(ForbiddenException)
        e.message.contains("Cannot transition project from archived to")
        e.message.contains("Archived projects can only be reactivated")

        where:
        targetStatus << [
            ProjectUpdate.StatusEnum.COMPLETED,
            ProjectUpdate.StatusEnum.CANCELLED
        ]
    }
    

    def 'should allow same status updates no-op'() {
        given: "a company id, project id, and update request with same status"
        def companyId = 1L
        def projectId = UUID.randomUUID().toString()
        def updateRequest = new ProjectUpdate(status: ProjectUpdate.StatusEnum.ACTIVE)

        and: "an ACTIVE project record"
        def projectRecord = new ProjectsRecord(id: projectId, companyId: companyId, status: "active")
        projectRepository.findById(projectId) >> projectRecord

        when: "the project status is updated to the same status"
        projectService.updateProject(companyId, projectId, updateRequest)

        then: "the repository's update method is called successfully"
        1 * projectRepository.update({ ProjectsRecord record ->
            record.status == "active"
        })
    }
    
    def "should retrieve projects with all valid statuses"(String status) {
        given: "a company id and project id"
        def companyId = 1L
        def projectId = UUID.randomUUID().toString()

        and: "a project record with the given status"
        def projectRecord = new ProjectsRecord(
            id: projectId, 
            companyId: companyId, 
            key: "TEST-KEY",
            name: "Test Project",
            description: "Test Description",
            status: status
        )
        projectRepository.findById(projectId) >> projectRecord

        when: "the project is retrieved"
        def result = projectService.getProjectById(companyId, projectId)

        then: "the project is returned with correct status mapping"
        result.id == projectId
        result.key == "TEST-KEY"
        result.name == "Test Project"
        result.description == "Test Description"
        result.status.value == status

        where:
        status << ["active", "archived", "completed", "cancelled"]
    }
    
    def "should prevent ANY updates to projects in final states"(String finalStatus, updateRequest) {
        given: "a company id, project id, and update request"
        def companyId = 1L
        def projectId = UUID.randomUUID().toString()

        and: "a project record with final status"
        def projectRecord = new ProjectsRecord(id: projectId, companyId: companyId, status: finalStatus)
        projectRepository.findById(projectId) >> projectRecord

        when: "any update is attempted on project in final state"
        projectService.updateProject(companyId, projectId, updateRequest)

        then: "a forbidden exception is thrown"
        def e = thrown(ForbiddenException)
        e.message.contains("Cannot update project in ${finalStatus} state")
        e.message.contains("Projects in final states (completed, cancelled) cannot be modified")

        where:
        finalStatus | updateRequest
        "completed" | new ProjectUpdate(name: "New Name")
        "completed" | new ProjectUpdate(description: "New Description")
        "completed" | new ProjectUpdate(status: ProjectUpdate.StatusEnum.ACTIVE)
        "completed" | new ProjectUpdate(status: ProjectUpdate.StatusEnum.ARCHIVED)
        "completed" | new ProjectUpdate(name: "New Name", description: "New Description")
        "cancelled" | new ProjectUpdate(name: "New Name")
        "cancelled" | new ProjectUpdate(description: "New Description")
        "cancelled" | new ProjectUpdate(status: ProjectUpdate.StatusEnum.ACTIVE)
        "cancelled" | new ProjectUpdate(status: ProjectUpdate.StatusEnum.ARCHIVED)
        "cancelled" | new ProjectUpdate(name: "New Name", description: "New Description")
    }
} 