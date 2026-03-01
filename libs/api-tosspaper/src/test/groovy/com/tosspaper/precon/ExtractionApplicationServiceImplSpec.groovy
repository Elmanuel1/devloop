package com.tosspaper.precon

import com.tosspaper.common.ApiErrorMessages
import com.tosspaper.common.BadRequestException
import com.tosspaper.common.NotFoundException
import com.tosspaper.precon.generated.model.Application
import com.tosspaper.precon.generated.model.ApplicationCreateRequest
import com.tosspaper.precon.generated.model.EntityType
import com.tosspaper.precon.generated.model.Extraction
import com.tosspaper.precon.generated.model.ExtractionStatus
import spock.lang.Specification

import java.time.OffsetDateTime

class ExtractionApplicationServiceImplSpec extends Specification {

    ExtractionService extractionService
    ExtractionApplicationServiceImpl service

    def COMPANY_ID   = 42L
    def EXTRACTION_ID = "11111111-1111-1111-1111-111111111111"
    def ENTITY_ID     = UUID.fromString("22222222-2222-2222-2222-222222222222")

    def setup() {
        extractionService = Mock()
        service = new ExtractionApplicationServiceImpl(extractionService)
    }

    // ==================== apply ====================

    def "TC-AS-A01: should return Application with all fields populated when extraction is COMPLETED"() {
        given: "a COMPLETED extraction and a valid application request"
            def request = new ApplicationCreateRequest()
            request.setEntityId(ENTITY_ID)

            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.COMPLETED)
            def result = new ExtractionResult(dto, 2)

        when: "applying the extraction"
            def application = service.apply(COMPANY_ID, EXTRACTION_ID, request)

        then: "extractionService is queried with correct identifiers"
            1 * extractionService.getExtraction(COMPANY_ID, EXTRACTION_ID) >> result

        and: "returned Application has all expected fields"
            with(application) {
                id != null
                extractionId == UUID.fromString(EXTRACTION_ID)
                entityId == ENTITY_ID
                fieldsApplied == []
                appliedAt != null
            }
    }

    def "TC-AS-A02: should throw BadRequestException when extraction is PENDING"() {
        given: "a PENDING extraction"
            def request = new ApplicationCreateRequest()
            request.setEntityId(ENTITY_ID)

            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.PENDING)
            def result = new ExtractionResult(dto, 0)

        when: "applying a non-completed extraction"
            service.apply(COMPANY_ID, EXTRACTION_ID, request)

        then: "extractionService returns the PENDING extraction"
            1 * extractionService.getExtraction(COMPANY_ID, EXTRACTION_ID) >> result

        and: "BadRequestException is thrown with extraction not-found code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE
            ex.message.contains("pending")
    }

    def "TC-AS-A03: should throw BadRequestException when extraction is PROCESSING"() {
        given: "a PROCESSING extraction"
            def request = new ApplicationCreateRequest()
            request.setEntityId(ENTITY_ID)

            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.PROCESSING)
            def result = new ExtractionResult(dto, 1)

        when: "applying the extraction"
            service.apply(COMPANY_ID, EXTRACTION_ID, request)

        then: "extractionService returns the PROCESSING extraction"
            1 * extractionService.getExtraction(COMPANY_ID, EXTRACTION_ID) >> result

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE
            ex.message.contains("processing")
    }

    def "TC-AS-A04: should throw BadRequestException when extraction is FAILED"() {
        given: "a FAILED extraction"
            def request = new ApplicationCreateRequest()
            request.setEntityId(ENTITY_ID)

            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.FAILED)
            def result = new ExtractionResult(dto, 1)

        when: "applying the extraction"
            service.apply(COMPANY_ID, EXTRACTION_ID, request)

        then: "extractionService returns the FAILED extraction"
            1 * extractionService.getExtraction(COMPANY_ID, EXTRACTION_ID) >> result

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE
            ex.message.contains("failed")
    }

    def "TC-AS-A05: should throw BadRequestException when extraction is CANCELLED"() {
        given: "a CANCELLED extraction"
            def request = new ApplicationCreateRequest()
            request.setEntityId(ENTITY_ID)

            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.CANCELLED)
            def result = new ExtractionResult(dto, 1)

        when: "applying the extraction"
            service.apply(COMPANY_ID, EXTRACTION_ID, request)

        then: "extractionService returns the CANCELLED extraction"
            1 * extractionService.getExtraction(COMPANY_ID, EXTRACTION_ID) >> result

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE
            ex.message.contains("cancelled")
    }

    def "TC-AS-A06: should propagate NotFoundException when extraction does not exist"() {
        given: "a valid request for a non-existent extraction"
            def request = new ApplicationCreateRequest()
            request.setEntityId(ENTITY_ID)

        when: "applying the extraction"
            service.apply(COMPANY_ID, EXTRACTION_ID, request)

        then: "extractionService throws NotFoundException"
            1 * extractionService.getExtraction(COMPANY_ID, EXTRACTION_ID) >> {
                throw new NotFoundException(ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                        ApiErrorMessages.EXTRACTION_NOT_FOUND)
            }

        and: "NotFoundException propagates without wrapping"
            thrown(NotFoundException)
    }

    def "TC-AS-A07: should generate a unique Application id on each call"() {
        given: "a COMPLETED extraction"
            def request = new ApplicationCreateRequest()
            request.setEntityId(ENTITY_ID)

            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.COMPLETED)
            def result = new ExtractionResult(dto, 2)
            extractionService.getExtraction(_, _) >> result

        when: "applying the extraction twice"
            def app1 = service.apply(COMPANY_ID, EXTRACTION_ID, request)
            def app2 = service.apply(COMPANY_ID, EXTRACTION_ID, request)

        then: "each Application has a distinct id"
            app1.id != null
            app2.id != null
            app1.id != app2.id
    }

    def "TC-AS-A08: should pass extractionId exactly as given to Application.extractionId"() {
        given: "a COMPLETED extraction with a known UUID string"
            def knownExtractionId = "aaaabbbb-cccc-dddd-eeee-ffffaaaabbbb"
            def entityId = UUID.fromString("12341234-1234-1234-1234-123412341234")
            def request = new ApplicationCreateRequest()
            request.setEntityId(entityId)

            def dto = createExtractionDto(knownExtractionId, entityId, ExtractionStatus.COMPLETED)
            def result = new ExtractionResult(dto, 3)

        when: "applying the extraction"
            def application = service.apply(COMPANY_ID, knownExtractionId, request)

        then: "extractionService is called with the known ID"
            1 * extractionService.getExtraction(COMPANY_ID, knownExtractionId) >> result

        and: "Application.extractionId matches the known UUID"
            application.extractionId == UUID.fromString(knownExtractionId)
            application.entityId == entityId
    }

    // ==================== Helper Methods ====================

    private static Extraction createExtractionDto(String id, UUID entityId, ExtractionStatus status) {
        def uuidId = id.length() == 36
            ? UUID.fromString(id)
            : UUID.fromString("00000000-0000-0000-0000-000000000001")
        def dto = new Extraction()
        dto.setId(uuidId)
        dto.setEntityType(EntityType.TENDER)
        dto.setEntityId(entityId)
        dto.setStatus(status)
        dto.setVersion(0)
        dto.setDocumentIds([])
        dto.setErrors([])
        dto.setCreatedAt(OffsetDateTime.now())
        return dto
    }
}
