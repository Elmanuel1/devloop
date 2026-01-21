package com.tosspaper.project

import com.tosspaper.common.DuplicateException
import com.tosspaper.common.NotFoundException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.jooq.tables.records.ProjectsRecord
import com.tosspaper.project.model.ProjectQuery
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Shared
import spock.lang.Subject

class ProjectRepositorySpec extends BaseIntegrationTest {

    @Autowired
    private DSLContext dsl

    @Subject
    private ProjectRepository projectRepository

    @Shared
    private Long companyId

    def setup() {
        projectRepository = new ProjectRepositoryImpl(dsl)

        // Create a company to satisfy foreign key constraints
        dsl.insertInto(Tables.COMPANIES)
                .set([
                        id: TestSecurityConfiguration.TEST_COMPANY_ID,
                        name: "Test Company",
                        email: "test@company.com"
                ])
                .execute()
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID
    }

    def cleanup() {
        dsl.deleteFrom(Tables.PURCHASE_ORDER_ITEMS).execute()
        dsl.delete(Tables.PURCHASE_ORDERS).execute()
        dsl.deleteFrom(Tables.PROJECTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }

    def "should create and find a project"() {
        given: "a project record"
        def projectRecord = new ProjectsRecord(
                companyId: companyId,
                key: "TEST",
                name: "Test Project",
                status: "active"
        )

        when: "the project is created"
        def createdRecord = projectRepository.create(projectRecord)

        then: "the record is created with an id"
        createdRecord.id != null
        createdRecord.key == "TEST"

        when: "the project is retrieved by id"
        def foundRecord = projectRepository.findById(createdRecord.id)

        then: "the correct record is returned"
        foundRecord.id == createdRecord.id
        foundRecord.name == "Test Project"
    }

    def "should create a project with all fields and return them"() {
        given: "a project record with all fields populated"
        def projectRecord = new ProjectsRecord(
                companyId: companyId,
                key: "FULL_PROJECT",
                name: "Full Project Name",
                status: "active",
                description: "A detailed project description."
        )

        when: "the project is created"
        def createdRecord = projectRepository.create(projectRecord)

        then: "all fields are correctly returned"
        createdRecord.id != null
        createdRecord.companyId == companyId
        createdRecord.key == "FULL_PROJECT"
        createdRecord.name == "Full Project Name"
        createdRecord.status == "active"
        createdRecord.description == "A detailed project description."
        createdRecord.createdAt != null
        createdRecord.updatedAt == null
    }

    def "should throw DuplicateException when creating a project with the same key"() {
        given: "an existing project"
        def projectRecord = new ProjectsRecord(
                companyId: companyId,
                key: "DUPE",
                name: "Original Project",
                status: "active"
        )
        projectRepository.create(projectRecord)

        and: "another project record with the same key"
        def duplicateRecord = new ProjectsRecord(
                companyId: companyId,
                key: "DUPE",
                name: "Duplicate Project",
                status: "active"
        )

        when: "creating the duplicate project"
        projectRepository.create(duplicateRecord)

        then: "a DuplicateException is thrown"
        thrown(DuplicateException)
    }

    def "should throw NotFoundException when finding a non-existent project"() {
        when: "finding a project with a non-existent id"
        projectRepository.findById("non-existent-id")

        then: "a NotFoundException is thrown"
        thrown(NotFoundException)
    }
    
    def "should update a project"() {
        given: "an existing project"
         def projectRecord = new ProjectsRecord(
                companyId: companyId,
                key: "UPDATE",
                name: "Project To Update",
                status: "active"
        )
        def createdRecord = projectRepository.create(projectRecord)

        when: "the project name is updated"
        createdRecord.setName("Updated Project Name")
        createdRecord.setDescription("Updated Project Description")
        createdRecord.setStatus("archived")
        def updatedRecord = projectRepository.update(createdRecord)

        then: "the name is updated"
        updatedRecord.name == "Updated Project Name"
        updatedRecord.description == "Updated Project Description"
        updatedRecord.status == "archived"
        updatedRecord.updatedAt != null
    }

    def "should throw DuplicateException when updating a project key to an existing one"() {
        given: "two existing projects"
        def project1 = projectRepository.create(new ProjectsRecord(companyId: companyId, key: "KEY1", name: "Project 1", status: "active"))
        projectRepository.create(new ProjectsRecord(companyId: companyId, key: "KEY2", name: "Project 2", status: "active"))

        when: "updating the key of the first project to match the second"
        project1.setKey("KEY2")
        projectRepository.update(project1)

        then: "a DuplicateException is thrown"
        thrown(DuplicateException)
    }

    def "should find projects by search term"() {
        given: "multiple projects with different names and keys"
        def project1 = projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "ANALYTICS",
                name: "Analytics Platform",
                status: "active",
                description: "Data analytics and reporting system"
        ))
        def project2 = projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "MOBILE_APP",
                name: "Mobile Application",
                status: "active",
                description: "Cross-platform mobile app"
        ))
        def project3 = projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "WEB_PORTAL",
                name: "Web Portal",
                status: "active",
                description: "Customer web portal"
        ))

        when: "searching for projects containing 'analytics'"
        def query = ProjectQuery.builder()
                .search("analytics")
                .page(1)
                .pageSize(10)
                .build()
        def results = projectRepository.find(companyId, query)

        then: "only the analytics project is returned"
        results.size() == 1
        results[0].id == project1.id
        results[0].name == "Analytics Platform"
    }

    def "should find projects by key search"() {
        given: "multiple projects with different keys"
        def project1 = projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "E_COMMERCE",
                name: "E-commerce Platform",
                status: "active"
        ))
        def project2 = projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "CRM_SYSTEM",
                name: "CRM System",
                status: "active"
        ))

        when: "searching for projects containing 'commerce'"
        def query = ProjectQuery.builder()
                .search("commerce")
                .page(1)
                .pageSize(10)
                .build()
        def results = projectRepository.find(companyId, query)

        then: "only the e-commerce project is returned"
        results.size() == 1
        results[0].id == project1.id
        results[0].key == "E_COMMERCE"
    }

    def "should find projects by description search"() {
        given: "projects with different descriptions"
        def project1 = projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "PAYMENT",
                name: "Payment System",
                status: "active",
                description: "Secure payment processing system"
        ))
        def project2 = projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "INVENTORY",
                name: "Inventory Management",
                status: "active",
                description: "Stock tracking and management"
        ))

        when: "searching for projects containing 'payment'"
        def query = ProjectQuery.builder()
                .search("payment")
                .page(1)
                .pageSize(10)
                .build()
        def results = projectRepository.find(companyId, query)

        then: "only the payment project is returned"
        results.size() == 1
        results[0].id == project1.id
        results[0].name == "Payment System"
    }

    def "should return empty results when no projects match search"() {
        given: "a project that doesn't match the search term"
        projectRepository.create(new ProjectsRecord(
                companyId: companyId,
                key: "TEST",
                name: "Test Project",
                status: "active"
        ))

        when: "searching for a term that doesn't match"
        def query = ProjectQuery.builder()
                .search("nonexistent")
                .page(1)
                .pageSize(10)
                .build()
        def results = projectRepository.find(companyId, query)

        then: "no results are returned"
        results.isEmpty()
    }

           def "should find projects with partial word matches"() {
               given: "projects with compound words"
               def project1 = projectRepository.create(new ProjectsRecord(
                       companyId: companyId,
                       key: "USER_MANAGEMENT",
                       name: "User Management System",
                       status: "active",
                       description: "Comprehensive user management"
               ))
               def project2 = projectRepository.create(new ProjectsRecord(
                       companyId: companyId,
                       key: "DATA_PROCESSING",
                       name: "Data Processing Engine",
                       status: "active",
                       description: "Real-time data processing"
               ))

               when: "searching for partial words"
               def query = ProjectQuery.builder()
                       .search("management")
                       .page(1)
                .pageSize(10)
                       .build()
               def results = projectRepository.find(companyId, query)

               then: "the user management project is found"
               results.size() == 1
               results[0].id == project1.id
               results[0].name == "User Management System"
           }

           def "should filter projects by status"() {
               given: "projects with different statuses"
               def activeProject = projectRepository.create(new ProjectsRecord(
                       companyId: companyId,
                       key: "ACTIVE_PROJECT",
                       name: "Active Project",
                       status: "active"
               ))
               def archivedProject = projectRepository.create(new ProjectsRecord(
                       companyId: companyId,
                       key: "ARCHIVED_PROJECT",
                       name: "Archived Project",
                       status: "archived"
               ))

               when: "filtering by active status"
               def query = ProjectQuery.builder()
                       .status("active")
                       .page(1)
                .pageSize(10)
                       .build()
               def results = projectRepository.find(companyId, query)

               then: "only active projects are returned"
               results.size() == 1
               results[0].id == activeProject.id
               results[0].status == "active"
           }

           def "should filter projects by archived status"() {
               given: "projects with different statuses"
               def activeProject = projectRepository.create(new ProjectsRecord(
                       companyId: companyId,
                       key: "ACTIVE_PROJECT",
                       name: "Active Project",
                       status: "active"
               ))
               def archivedProject = projectRepository.create(new ProjectsRecord(
                       companyId: companyId,
                       key: "ARCHIVED_PROJECT",
                       name: "Archived Project",
                       status: "archived"
               ))

               when: "filtering by archived status"
               def query = ProjectQuery.builder()
                       .status("archived")
                       .page(1)
                .pageSize(10)
                       .build()
               def results = projectRepository.find(companyId, query)

               then: "only archived projects are returned"
               results.size() == 1
               results[0].id == archivedProject.id
               results[0].status == "archived"
           }

           def "should handle offset-based pagination correctly"() {
               given: "8 projects created in sequence"
               def projects = []
               for (int i = 1; i <= 8; i++) {
                   def project = projectRepository.create(new ProjectsRecord(
                           companyId: companyId,
                           key: "PROJECT_${i}",
                           name: "Project ${i}",
                           status: "active"
                   ))
                   projects.add(project)
                   // Small delay to ensure different timestamps
                   Thread.sleep(10)
               }
               // projects[0] = oldest, projects[7] = newest

               when: "Get first page (3 projects, ordered by created_at DESC)"
               def page1Query = ProjectQuery.builder()
                       .page(1)
                       .pageSize(3)
                       .build()
               def page1Results = projectRepository.find(companyId, page1Query)

               and: "Get second page"
               def page2Query = ProjectQuery.builder()
                       .page(2)
                       .pageSize(3)
                       .build()
               def page2Results = projectRepository.find(companyId, page2Query)

               and: "Get third page"
               def page3Query = ProjectQuery.builder()
                       .page(3)
                       .pageSize(3)
                       .build()
               def page3Results = projectRepository.find(companyId, page3Query)

               then: "Page 1 contains the 3 newest projects (DESC order)"
               page1Results.size() == 3
               page1Results[0].id == projects[7].id // Newest (Project 8)
               page1Results[1].id == projects[6].id // Project 7
               page1Results[2].id == projects[5].id // Project 6

               and: "Page 2 contains the next 3 projects"
               page2Results.size() == 3
               page2Results[0].id == projects[4].id // Project 5
               page2Results[1].id == projects[3].id // Project 4
               page2Results[2].id == projects[2].id // Project 3

               and: "Page 3 contains the remaining 2 oldest projects"
               page3Results.size() == 2
               page3Results[0].id == projects[1].id // Project 2
               page3Results[1].id == projects[0].id // Oldest (Project 1)
           }
} 