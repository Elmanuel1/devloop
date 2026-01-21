package com.tosspaper.company

import com.tosspaper.common.DuplicateException
import com.tosspaper.common.NotFoundException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.jooq.tables.Companies
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Unroll
import org.jooq.DSLContext

class CompanyRepositorySpec extends BaseIntegrationTest {

    @Autowired
    private CompanyRepository companyRepository

    @Autowired
    private DSLContext dsl

    def cleanup() {
        dsl.deleteFrom(Companies.COMPANIES).execute()
    }

    def "should save and retrieve a company"() {
        given: "a new company record"
            def newCompany = new CompaniesRecord(
                name: "Test Company",
                email: "test@company.com"
            )

        when: "the company is saved"
           def savedCompany = companyRepository.save(newCompany)

        then: "the saved company has an ID and matches the input"
            savedCompany.id != null
            savedCompany.name == "Test Company"
            savedCompany.email == "test@company.com"

        and: "it can be retrieved by ID"
           def foundCompany = companyRepository.findById(savedCompany.id)
        then: 
            foundCompany.name == savedCompany.name
            foundCompany.email == savedCompany.email
            foundCompany.createdAt != null
            foundCompany.updatedAt == null
    }

    def "should throw NotFoundException when retrieving a non-existent company"() {
        when: "retrieving a company with a non-existent ID"
        companyRepository.findById(-1L)

        then: "a NotFoundException is thrown"
        thrown(NotFoundException)
    }

    def "should find a company by email"() {
        given: "an existing company"
        companyRepository.save(new CompaniesRecord(name: "Find Me Inc.", email: "find.me@company.com"))

        when: "searching for the company by email"
        def foundCompany = companyRepository.findByEmail("find.me@company.com")

        then: "the company is found"
        foundCompany.isPresent()
        foundCompany.get().name == "Find Me Inc."
    }

    def "should return empty optional for non-existent email"() {
        when: "searching for a company with a non-existent email"
        def foundCompany = companyRepository.findByEmail("not.found@company.com")

        then: "an empty optional is returned"
        foundCompany.isEmpty()
    }

    def "should throw DuplicateCompanyException for duplicate email on save"() {
        given: "an existing company"
        companyRepository.save(new CompaniesRecord(name: "Original", email: "duplicate@company.com"))

        when: "saving a new company with the same email"
        companyRepository.save(new CompaniesRecord(name: "Duplicate", email: "duplicate@company.com"))

        then: "a DuplicateCompanyException is thrown"
        thrown(DuplicateException)
    }

    @Unroll
    def "should handle various invalid inputs for save"() {
        when: "saving a company with invalid data"
        companyRepository.save(new CompaniesRecord(name: name, email: email))

        then: "an exception is thrown"
        thrown(Exception) // Or a more specific exception if you have constraints

        where:
        name     | email
        null     | "valid@email.com"
        "No Email" | null
    }

    def "should save and retrieve a company with all fields"() {
        given: "a new company record with all fields populated"
        def newCompany = new CompaniesRecord(
            name: "Full Test Company",
            email: "full@company.com",
            description: "A full description",
            logoUrl: "http://logo.url/full",
            termsUrl: "http://terms.url/full",
            privacyUrl: "http://privacy.url/full"
        )

        when: "the company is saved"
        def savedCompany = companyRepository.save(newCompany)

        then: "the saved company has all fields populated correctly"
        savedCompany.id != null
        savedCompany.name == "Full Test Company"
        savedCompany.email == "full@company.com"
        savedCompany.description == "A full description"
        savedCompany.logoUrl == "http://logo.url/full"
        savedCompany.termsUrl == "http://terms.url/full"
        savedCompany.privacyUrl == "http://privacy.url/full"
    }

    def "should save and retrieve a company with only required fields"() {
        given: "a new company record with only required fields"
        def newCompany = new CompaniesRecord(
            name: "Minimal Test Company",
            email: "minimal@company.com"
        )

        when: "the company is saved"
        def savedCompany = companyRepository.save(newCompany)

        then: "the saved company has an ID and null for optional fields"
        savedCompany.id != null
        savedCompany.name == "Minimal Test Company"
        savedCompany.email == "minimal@company.com"
        savedCompany.description == null
        savedCompany.logoUrl == null
        savedCompany.termsUrl == null
        savedCompany.privacyUrl == null
    }
} 