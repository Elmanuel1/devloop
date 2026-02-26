package com.tosspaper.precon

import com.tosspaper.common.ApiErrorMessages
import com.tosspaper.common.BadRequestException
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import com.tosspaper.precon.generated.model.EntityType
import com.tosspaper.precon.generated.model.Extraction
import com.tosspaper.precon.generated.model.ExtractionCreateRequest
import com.tosspaper.precon.generated.model.ExtractionStatus
import org.jooq.JSONB
import spock.lang.Specification

import java.time.OffsetDateTime

class ExtractionServiceImplSpec extends Specification {

    ExtractionRepository extractionRepository
    ExtractionFieldRepository extractionFieldRepository
    ExtractionMapper extractionMapper
    ExtractionJsonConverter jsonConverter
    EntityExtractionAdapter tenderAdapter
    ExtractionServiceImpl service

    def COMPANY_ID = 42L
    def COMPANY_ID_STR = "42"
    def ENTITY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    def ENTITY_ID_STR = "11111111-1111-1111-1111-111111111111"
    def EXTRACTION_ID = "ext-0001"

    def setup() {
        extractionRepository = Mock()
        extractionFieldRepository = Mock()
        extractionMapper = Mock()
        jsonConverter = Mock()
        tenderAdapter = Mock()
        tenderAdapter.entityType() >> EntityType.TENDER

        service = new ExtractionServiceImpl(
            extractionRepository,
            extractionFieldRepository,
            extractionMapper,
            jsonConverter,
            [tenderAdapter]
        )
    }

    // ==================== createExtraction ====================

    def "TC-S-C01: should create extraction with explicit document IDs and field names"() {
        given: "a valid create request with documents and fields"
            def docId1 = UUID.fromString("aaaa1111-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def docId2 = UUID.fromString("bbbb2222-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setDocumentIds([docId1, docId2])
            request.setFields(["closing_date", "location"])

            def resolvedDocIds = [docId1.toString(), docId2.toString()]
            def validatedFields = ["closing_date", "location"]
            def insertedRecord = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)
            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.PENDING, 0)

        when: "creating an extraction"
            def result = service.createExtraction(COMPANY_ID, request)

        then: "adapter verifies ownership"
            1 * tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> true

        and: "adapter resolves document IDs"
            1 * tenderAdapter.resolveDocumentIds(ENTITY_ID_STR, request) >> resolvedDocIds

        and: "adapter validates field names"
            1 * tenderAdapter.validateFieldNames(["closing_date", "location"]) >> validatedFields

        and: "JSON converter serializes document IDs"
            1 * jsonConverter.stringListToJsonb(resolvedDocIds) >> JSONB.valueOf('["aaaa1111-aaaa-aaaa-aaaa-aaaaaaaaaaaa","bbbb2222-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]')

        and: "JSON converter serializes field names"
            1 * jsonConverter.stringListToJsonb(validatedFields) >> JSONB.valueOf('["closing_date","location"]')

        and: "mapper builds the record from params"
            1 * extractionMapper.toRecord({ ExtractionInsertParams p ->
                p.companyId() == COMPANY_ID_STR &&
                p.entityType() == EntityType.TENDER &&
                p.entityId() == ENTITY_ID_STR
            }) >> insertedRecord

        and: "repository inserts the record"
            1 * extractionRepository.insert(insertedRecord) >> insertedRecord

        and: "mapper converts to DTO"
            1 * extractionMapper.toDto(insertedRecord) >> dto

        and: "converter deserializes errors from JSONB"
            1 * jsonConverter.jsonbToErrorList(_) >> []

        and: "result has PENDING status and version 0"
            with(result) {
                extraction.id != null
                extraction.status == ExtractionStatus.PENDING
                version == 0
            }
    }

    def "TC-S-C02: should create extraction with null fields (no field filter)"() {
        given: "a create request without field filter"
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setFields(null)

            def resolvedDocIds = ["doc-111"]
            def insertedRecord = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)
            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.PENDING, 0)

        when: "creating extraction without fields"
            def result = service.createExtraction(COMPANY_ID, request)

        then: "adapter verifies ownership"
            1 * tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> true

        and: "adapter resolves documents"
            1 * tenderAdapter.resolveDocumentIds(ENTITY_ID_STR, request) >> resolvedDocIds

        and: "adapter validates null fields and returns null"
            1 * tenderAdapter.validateFieldNames(null) >> null

        and: "document IDs serialized but field names NOT serialized (null fieldNames)"
            1 * jsonConverter.stringListToJsonb(resolvedDocIds) >> JSONB.valueOf('["doc-111"]')
            0 * jsonConverter.stringListToJsonb(null)

        and: "mapper builds record with null fieldNames"
            1 * extractionMapper.toRecord({ ExtractionInsertParams p ->
                p.fieldNames() == null
            }) >> insertedRecord

        and: "record is inserted"
            1 * extractionRepository.insert(insertedRecord) >> insertedRecord

        and: "mapper and converter called"
            1 * extractionMapper.toDto(insertedRecord) >> dto
            1 * jsonConverter.jsonbToErrorList(_) >> []

        and: "result has version 0"
            result.version == 0
    }

    def "TC-S-C03: should auto-resolve documents when documentIds list is empty"() {
        given: "a create request with empty documentIds (auto-resolve path)"
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setDocumentIds([])  // empty list, not null

            def autoResolvedDocIds = ["auto-doc-1", "auto-doc-2", "auto-doc-3"]
            def insertedRecord = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)
            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.PENDING, 0)

        when: "creating extraction with empty documentIds"
            def result = service.createExtraction(COMPANY_ID, request)

        then: "adapter verifies ownership"
            1 * tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> true

        and: "adapter resolves documents (auto-resolve because list is empty)"
            1 * tenderAdapter.resolveDocumentIds(ENTITY_ID_STR, request) >> autoResolvedDocIds

        and: "adapter validates fields"
            1 * tenderAdapter.validateFieldNames(_) >> null

        and: "auto-resolved IDs are serialized"
            1 * jsonConverter.stringListToJsonb(autoResolvedDocIds) >> JSONB.valueOf('["auto-doc-1","auto-doc-2","auto-doc-3"]')

        and: "mapper builds record and repository inserts"
            1 * extractionMapper.toRecord(_) >> insertedRecord
            1 * extractionRepository.insert(insertedRecord) >> insertedRecord

        and: "mapper and converter called"
            1 * extractionMapper.toDto(insertedRecord) >> dto
            1 * jsonConverter.jsonbToErrorList(_) >> []

        and: "result has PENDING status"
            result.extraction.status == ExtractionStatus.PENDING
            result.version == 0
    }

    def "TC-S-C04: should throw BadRequestException for unsupported entity type"() {
        given: "no adapter registered — empty adapter list means TENDER is unsupported"
            def serviceNoAdapter = new ExtractionServiceImpl(
                extractionRepository, extractionFieldRepository, extractionMapper,
                jsonConverter, []  // no adapters registered
            )
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)

        when: "creating extraction with no registered adapters"
            serviceNoAdapter.createExtraction(COMPANY_ID, request)

        then: "BadRequestException thrown with entityTypeNotSupported code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.ENTITY_TYPE_NOT_SUPPORTED_CODE
    }

    def "TC-S-C05: should throw NotFoundException when verifyOwnership fails (wrong company)"() {
        given: "adapter reports entity does not belong to company"
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setDocumentIds([])
            request.setFields([])

        when: "creating extraction"
            service.createExtraction(COMPANY_ID, request)

        then: "adapter denies ownership"
            1 * tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> false

        and: "NotFoundException thrown before any insert"
            def ex = thrown(NotFoundException)
            ex.code == "api.extraction.notFound"

        and: "repository insert never called"
            0 * extractionRepository.insert(_)
    }

    def "TC-S-C06: should propagate BadRequestException when no ready documents available"() {
        given: "adapter finds no ready documents and throws"
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setDocumentIds([])

        when: "creating extraction"
            service.createExtraction(COMPANY_ID, request)

        then: "adapter verifies ownership successfully"
            1 * tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> true

        and: "adapter throws when no ready documents"
            1 * tenderAdapter.resolveDocumentIds(ENTITY_ID_STR, request) >> {
                throw new BadRequestException(ApiErrorMessages.EXTRACTION_NO_READY_DOCS_CODE, ApiErrorMessages.EXTRACTION_NO_READY_DOCS)
            }

        and: "BadRequestException propagates"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_NO_READY_DOCS_CODE

        and: "repository insert never called"
            0 * extractionRepository.insert(_)
    }

    def "TC-S-C07: should propagate BadRequestException when field name is invalid"() {
        given: "adapter rejects invalid field name"
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setDocumentIds([])
            request.setFields(["invalid_field_xyz"])

        when: "creating extraction"
            service.createExtraction(COMPANY_ID, request)

        then: "adapter verifies ownership"
            1 * tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> true

        and: "adapter resolves documents"
            1 * tenderAdapter.resolveDocumentIds(ENTITY_ID_STR, request) >> ["doc-1"]

        and: "adapter rejects the field name"
            1 * tenderAdapter.validateFieldNames(["invalid_field_xyz"]) >> {
                throw new BadRequestException(ApiErrorMessages.EXTRACTION_INVALID_FIELD_CODE, ApiErrorMessages.EXTRACTION_INVALID_FIELD)
            }

        and: "BadRequestException propagates"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_INVALID_FIELD_CODE

        and: "repository insert never called"
            0 * extractionRepository.insert(_)
    }

    def "TC-S-C08: should propagate RuntimeException when repository insert throws DB error"() {
        given: "repository will throw on insert"
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setDocumentIds([])

            tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> true
            tenderAdapter.resolveDocumentIds(ENTITY_ID_STR, request) >> ["doc-1"]
            tenderAdapter.validateFieldNames(_) >> null
            jsonConverter.stringListToJsonb(["doc-1"]) >> JSONB.valueOf('["doc-1"]')
            extractionMapper.toRecord(_) >> new ExtractionsRecord()

        when: "creating extraction and DB fails"
            service.createExtraction(COMPANY_ID, request)

        then: "repository insert throws"
            1 * extractionRepository.insert(_) >> { throw new RuntimeException("DB connection error") }

        and: "exception propagates wrapped in RuntimeException"
            thrown(RuntimeException)
    }

    def "TC-S-C09: should propagate BadRequestException when tender is in terminal status"() {
        given: "adapter rejects tender in won/lost/cancelled status"
            def request = new ExtractionCreateRequest()
            request.setEntityId(ENTITY_ID)
            request.setEntityType(EntityType.TENDER)
            request.setDocumentIds([])

        when: "creating extraction for terminal-status tender"
            service.createExtraction(COMPANY_ID, request)

        then: "adapter throws because tender is in terminal status"
            1 * tenderAdapter.verifyOwnership(COMPANY_ID_STR, ENTITY_ID_STR) >> {
                throw new BadRequestException("api.extraction.tenderNotActive", "Tender is not active")
            }

        and: "BadRequestException propagates"
            def ex = thrown(BadRequestException)
            ex.code == "api.extraction.tenderNotActive"

        and: "no further operations called"
            0 * extractionRepository.insert(_)
    }

    // ==================== listExtractions ====================

    def "TC-S-L01: should return list with null cursor when no more pages"() {
        given: "repository returns exactly limit records (no extra)"
            def entityId = ENTITY_ID
            def limit = 5
            def r1 = createExtractionRecord("e1", COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)
            def r2 = createExtractionRecord("e2", COMPANY_ID_STR, ENTITY_ID_STR, "processing", 0)
            def records = [r1, r2]
            def dto1 = createExtractionDto("e1", ENTITY_ID, ExtractionStatus.PENDING, 0)
            def dto2 = createExtractionDto("e2", ENTITY_ID, ExtractionStatus.PROCESSING, 0)

        when: "listing extractions"
            def result = service.listExtractions(COMPANY_ID, entityId, null, limit, null)

        then: "repository called with correct query"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, {
                it.entityId == ENTITY_ID_STR &&
                it.status == null &&
                it.limit == 5 &&
                it.cursorCreatedAt == null &&
                it.cursorId == null
            }) >> records

        and: "mapper converts each record"
            1 * extractionMapper.toDto(r1) >> dto1
            1 * extractionMapper.toDto(r2) >> dto2
            2 * jsonConverter.jsonbToErrorList(_) >> []

        and: "result has 2 items and null cursor"
            with(result) {
                data.size() == 2
                pagination.cursor == null
            }
    }

    def "TC-S-L02: should return cursor when there are more pages"() {
        given: "repository returns limit+1 records (indicating more pages)"
            def entityId = ENTITY_ID
            def limit = 3
            def r1 = createExtractionRecord("e1", COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)
            def r2 = createExtractionRecord("e2", COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)
            def r3 = createExtractionRecord("e3", COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)
            def r4 = createExtractionRecord("e4", COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0) // extra record
            def records = [r1, r2, r3, r4]

            def dto1 = createExtractionDto("e1", ENTITY_ID, ExtractionStatus.PENDING, 0)
            def dto2 = createExtractionDto("e2", ENTITY_ID, ExtractionStatus.PENDING, 0)
            def dto3 = createExtractionDto("e3", ENTITY_ID, ExtractionStatus.PENDING, 0)

        when: "listing with limit 3"
            def result = service.listExtractions(COMPANY_ID, entityId, null, limit, null)

        then: "repository called with limit 3"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, { it.limit == 3 }) >> records

        and: "mapper only converts the first 3 records"
            1 * extractionMapper.toDto(r1) >> dto1
            1 * extractionMapper.toDto(r2) >> dto2
            1 * extractionMapper.toDto(r3) >> dto3
            0 * extractionMapper.toDto(r4)
            3 * jsonConverter.jsonbToErrorList(_) >> []

        and: "result has 3 items and non-null cursor pointing to last returned record"
            with(result) {
                data.size() == 3
                pagination.cursor != null
            }
    }

    def "TC-S-L03: should return empty data with null cursor when no extractions"() {
        given: "repository returns empty list"
            def entityId = ENTITY_ID

        when: "listing extractions for entity with none"
            def result = service.listExtractions(COMPANY_ID, entityId, null, 20, null)

        then: "repository called"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, _) >> []

        and: "no mapper calls"
            0 * extractionMapper.toDto(_)

        and: "result is empty with null cursor"
            with(result) {
                data.isEmpty()
                pagination.cursor == null
            }
    }

    def "TC-S-L04: should pass status filter to repository"() {
        given: "filtering by processing status"
            def entityId = ENTITY_ID

        when: "listing with status=processing"
            def result = service.listExtractions(COMPANY_ID, entityId, ExtractionStatus.PROCESSING, 20, null)

        then: "repository called with status=processing"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, {
                it.status == "processing"
            }) >> []

        and: "result is empty"
            result.data.isEmpty()
    }

    def "TC-S-L05: should pass null status to repository when not filtered"() {
        given: "no status filter"

        when: "listing without status filter"
            service.listExtractions(COMPANY_ID, ENTITY_ID, null, 20, null)

        then: "repository called with null status"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, {
                it.status == null
            }) >> []
    }

    def "TC-S-L06: should default limit to 20 when null"() {
        when: "listing with null limit"
            service.listExtractions(COMPANY_ID, ENTITY_ID, null, null, null)

        then: "repository called with effective limit 20"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, {
                it.limit == 20
            }) >> []
    }

    def "TC-S-L07: should reset limit to 20 when greater than 100"() {
        when: "listing with limit=150"
            service.listExtractions(COMPANY_ID, ENTITY_ID, null, 150, null)

        then: "repository called with effective limit 20"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, {
                it.limit == 20
            }) >> []
    }

    def "TC-S-L08: should reset limit to 20 when less than 1"() {
        when: "listing with limit=0"
            service.listExtractions(COMPANY_ID, ENTITY_ID, null, 0, null)

        then: "repository called with effective limit 20"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, {
                it.limit == 20
            }) >> []
    }

    def "TC-S-L09: should parse cursor and pass cursorCreatedAt and cursorId to query"() {
        given: "a valid cursor encoding a createdAt and ID"
            def createdAt = OffsetDateTime.parse("2026-01-15T10:30:00Z")
            def cursorId = "e-cursor-123"
            def encodedCursor = com.tosspaper.common.CursorUtils.encodeCursor(createdAt, cursorId)

        when: "listing with a cursor"
            service.listExtractions(COMPANY_ID, ENTITY_ID, null, 5, encodedCursor)

        then: "repository called with decoded cursor values"
            1 * extractionRepository.findByEntityId(COMPANY_ID_STR, ENTITY_ID_STR, {
                it.cursorCreatedAt != null &&
                it.cursorId == cursorId &&
                it.limit == 5
            }) >> []
    }

    def "TC-S-L10: should scope results by companyId — different company sees empty results"() {
        given: "company 999 has no extractions for the entity"

        when: "listing for company 999"
            def result = service.listExtractions(999L, ENTITY_ID, null, 20, null)

        then: "repository called with company 999's ID"
            1 * extractionRepository.findByEntityId("999", ENTITY_ID_STR, _) >> []

        and: "empty result returned — scoped to DB layer"
            result.data.isEmpty()
            result.pagination.cursor == null
    }

    // ==================== getExtraction ====================

    def "TC-S-G01: should return extraction result with correct status, version, and errors list"() {
        given: "an extraction belonging to the company at version 2"
            // Note: V3.5 fields (started_at, completed_at, errors) are not in the generated jOOQ
            // schema. getOptionalField() in ExtractionServiceImpl returns null for them gracefully.
            // The DTO's startedAt/completedAt come from the DB in integration tests; here they are null.
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 2)
            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.COMPLETED, 2)

        when: "getting extraction"
            def result = service.getExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "mapper converts record to DTO"
            1 * extractionMapper.toDto(record) >> dto

        and: "error list deserialized from null JSONB (V3.5 field absent) — returns empty list"
            1 * jsonConverter.jsonbToErrorList(null) >> []

        and: "result has correct status, errors, and version=2"
            with(result) {
                extraction.status == ExtractionStatus.COMPLETED
                extraction.errors == []
                version == 2
            }
    }

    def "TC-S-G02: should throw NotFoundException when extraction DB row is missing"() {
        when: "getting nonexistent extraction"
            service.getExtraction(COMPANY_ID, "nonexistent-ext")

        then: "repository throws NotFoundException"
            1 * extractionRepository.findById("nonexistent-ext") >> {
                throw new NotFoundException("api.extraction.notFound", "Extraction not found.")
            }

        and: "NotFoundException propagates"
            thrown(NotFoundException)
    }

    def "TC-S-G03: should throw NotFoundException when extraction belongs to different company"() {
        given: "extraction exists but belongs to a different company"
            def record = createExtractionRecord(EXTRACTION_ID, "999", ENTITY_ID_STR, "pending", 0)

        when: "getting extraction for company 42 but record belongs to 999"
            service.getExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository returns record with different company"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "NotFoundException thrown with extraction code"
            def ex = thrown(NotFoundException)
            ex.code == "api.extraction.notFound"
    }

    def "TC-S-G04: should return empty errors list when errors JSONB is null"() {
        given: "an extraction where errors V3.5 field is absent (not in generated jOOQ schema)"
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 1)
            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.COMPLETED, 1)

        when: "getting extraction"
            def result = service.getExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds record"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "mapper converts record"
            1 * extractionMapper.toDto(record) >> dto

        and: "jsonbToErrorList called with null and returns empty list"
            1 * jsonConverter.jsonbToErrorList(null) >> []

        and: "errors is empty list, not null"
            result.extraction.errors == []
    }

    def "TC-S-G05: should not throw when FAILED extraction has no V3.5 fields — errors defaults to empty list"() {
        given: "a FAILED extraction where V3.5 fields (started_at, completed_at, errors) are absent from jOOQ schema"
            // In unit tests the generated ExtractionsRecord schema predates the V3.5 migration.
            // safeGet() iterates record.fields() and finds no match, returning null for all three.
            // The service must not throw and must return errors=[] via the converter.
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "failed", 1)
            def dto = createExtractionDto(EXTRACTION_ID, ENTITY_ID, ExtractionStatus.FAILED, 1)

        when: "getting a failed extraction"
            def result = service.getExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds record"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "mapper converts record"
            1 * extractionMapper.toDto(record) >> dto

        and: "jsonConverter called with null JSONB (field absent) and returns empty list"
            1 * jsonConverter.jsonbToErrorList(null) >> []

        and: "no exception thrown and errors defaults to empty list"
            noExceptionThrown()
            result.extraction.errors == []
            result.extraction.status == ExtractionStatus.FAILED
    }

    // ==================== cancelExtraction ====================

    def "TC-S-X01: should cancel PENDING extraction — updateStatus and deleteByExtractionId called"() {
        given: "a PENDING extraction"
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)

        when: "cancelling the extraction"
            service.cancelExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "status updated to cancelled"
            1 * extractionRepository.updateStatus(EXTRACTION_ID, "cancelled")

        and: "fields deleted for the extraction"
            1 * extractionFieldRepository.deleteByExtractionId(EXTRACTION_ID)
    }

    def "TC-S-X02: should cancel PROCESSING extraction — updateStatus and deleteByExtractionId called"() {
        given: "a PROCESSING extraction"
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "processing", 0)

        when: "cancelling the extraction"
            service.cancelExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "status updated to cancelled"
            1 * extractionRepository.updateStatus(EXTRACTION_ID, "cancelled")

        and: "fields deleted"
            1 * extractionFieldRepository.deleteByExtractionId(EXTRACTION_ID)
    }

    def "TC-S-X03: should throw BadRequestException when extraction is already CANCELLED"() {
        given: "an already CANCELLED extraction"
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "cancelled", 1)

        when: "attempting to cancel again"
            service.cancelExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "BadRequestException thrown — already-cancelled is an invalid state"
            def ex = thrown(BadRequestException)
            ex.code == "api.extraction.cannotCancel"

        and: "updateStatus never called"
            0 * extractionRepository.updateStatus(_, _)

        and: "deleteByExtractionId never called"
            0 * extractionFieldRepository.deleteByExtractionId(_)
    }

    def "TC-S-X04: should throw BadRequestException when cancelling a COMPLETED extraction"() {
        given: "a COMPLETED extraction"
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 2)

        when: "cancelling a completed extraction"
            service.cancelExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "BadRequestException thrown"
            def ex = thrown(BadRequestException)
            ex.code == "api.extraction.cannotCancel"

        and: "no status update"
            0 * extractionRepository.updateStatus(_, _)
    }

    def "TC-S-X05: should throw BadRequestException when cancelling a FAILED extraction"() {
        given: "a FAILED extraction"
            def record = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "failed", 1)

        when: "cancelling a failed extraction"
            service.cancelExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository finds the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "BadRequestException thrown — failed is not cancellable"
            def ex = thrown(BadRequestException)
            ex.code == "api.extraction.cannotCancel"

        and: "no mutations"
            0 * extractionRepository.updateStatus(_, _)
            0 * extractionFieldRepository.deleteByExtractionId(_)
    }

    def "TC-S-X06: should propagate NotFoundException when extraction not found"() {
        when: "cancelling nonexistent extraction"
            service.cancelExtraction(COMPANY_ID, "nonexistent-ext")

        then: "repository throws NotFoundException"
            1 * extractionRepository.findById("nonexistent-ext") >> {
                throw new NotFoundException("api.extraction.notFound", "Extraction not found.")
            }

        and: "NotFoundException propagates"
            thrown(NotFoundException)
    }

    def "TC-S-X07: should throw NotFoundException when extraction belongs to different company"() {
        given: "extraction belongs to company 999, request is from company 42"
            def record = createExtractionRecord(EXTRACTION_ID, "999", ENTITY_ID_STR, "pending", 0)

        when: "cancelling extraction for wrong company"
            service.cancelExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository returns record with different company"
            1 * extractionRepository.findById(EXTRACTION_ID) >> record

        and: "NotFoundException thrown"
            def ex = thrown(NotFoundException)
            ex.code == "api.extraction.notFound"

        and: "no mutations"
            0 * extractionRepository.updateStatus(_, _)
            0 * extractionFieldRepository.deleteByExtractionId(_)
    }

    def "TC-S-X08: should propagate NotFoundException when extraction is not found (entity deleted scenario)"() {
        given: "extraction not found because it was already removed"

        when: "cancelling extraction"
            service.cancelExtraction(COMPANY_ID, EXTRACTION_ID)

        then: "repository cannot find the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> {
                throw new NotFoundException("api.extraction.notFound", "Extraction not found.")
            }

        and: "NotFoundException propagates"
            thrown(NotFoundException)
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a plain ExtractionsRecord with standard typed fields.
     * V3.5 fields (started_at, completed_at, errors) are not in the generated jOOQ schema.
     * ExtractionServiceImpl.getOptionalField() handles their absence gracefully (returns null).
     */
    private static ExtractionsRecord createExtractionRecord(String id, String companyId,
                                                             String entityId, String status,
                                                             int version) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setCompanyId(companyId)
        record.setEntityType("tender")
        record.setEntityId(entityId)
        record.setStatus(status)
        record.setVersion(version)
        record.setCreatedBy(companyId)
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }

    private static Extraction createExtractionDto(String id, UUID entityId,
                                                   ExtractionStatus status, int version) {
        def idUuid = id.length() == 36
            ? UUID.fromString(id)
            : UUID.fromString("00000000-0000-0000-0000-000000000001")
        def dto = new Extraction()
        dto.setId(idUuid)
        dto.setEntityType(EntityType.TENDER)
        dto.setEntityId(entityId)
        dto.setStatus(status)
        dto.setVersion(version)
        dto.setDocumentIds([])
        dto.setErrors([])
        dto.setCreatedAt(OffsetDateTime.now())
        return dto
    }
}
