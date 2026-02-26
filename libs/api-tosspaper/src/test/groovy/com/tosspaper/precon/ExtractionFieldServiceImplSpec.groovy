package com.tosspaper.precon

import com.tosspaper.common.ApiErrorMessages
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.exception.IfMatchRequiredException
import com.tosspaper.models.exception.StaleVersionException
import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import com.tosspaper.precon.generated.model.EntityType
import com.tosspaper.precon.generated.model.ExtractionField
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateRequest
import com.tosspaper.precon.generated.model.ExtractionFieldUpdateItem
import org.jooq.JSONB
import spock.lang.Specification

import java.time.OffsetDateTime

class ExtractionFieldServiceImplSpec extends Specification {

    ExtractionRepository extractionRepository
    ExtractionFieldRepository extractionFieldRepository
    ExtractionFieldMapper extractionFieldMapper
    ExtractionJsonConverter jsonConverter
    ExtractionFieldServiceImpl service

    def COMPANY_ID = 42L
    def COMPANY_ID_STR = "42"
    def ENTITY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    def ENTITY_ID_STR = "11111111-1111-1111-1111-111111111111"
    def EXTRACTION_ID = "ext-0001"

    def setup() {
        extractionRepository = Mock()
        extractionFieldRepository = Mock()
        extractionFieldMapper = Mock()
        jsonConverter = Mock()

        service = new ExtractionFieldServiceImpl(
            extractionRepository,
            extractionFieldRepository,
            extractionFieldMapper,
            jsonConverter
        )
    }

    // ==================== listExtractionFields ====================

    def "TC-S-F01: should return fields with entityType and entityId from parent extraction"() {
        given: "an extraction belonging to the company with two fields"
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 2)
            def field1 = createFieldRecord("field-1", EXTRACTION_ID, "closing_date")
            def field2 = createFieldRecord("field-2", EXTRACTION_ID, "location")
            def records = [field1, field2]
            def dto1 = new ExtractionField()
            def dto2 = new ExtractionField()

        when: "listing extraction fields"
            def result = service.listExtractionFields(COMPANY_ID, EXTRACTION_ID, null, null, 20, null)

        then: "repository finds the extraction"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "field repository queried with correct extractionId"
            1 * extractionFieldRepository.findByExtractionId({
                it.extractionId == EXTRACTION_ID &&
                it.fieldName == null &&
                it.documentId == null &&
                it.limit == 20 &&
                it.cursorCreatedAt == null &&
                it.cursorId == null
            }) >> records

        and: "mapper converts with entityType=TENDER and correct entityId"
            1 * extractionFieldMapper.toDtoList(records, EntityType.TENDER, ENTITY_ID) >> [dto1, dto2]

        and: "result has 2 fields with null cursor"
            with(result) {
                data.size() == 2
                pagination.cursor == null
            }
    }

    def "TC-S-F02: should return cursor when there are more pages"() {
        given: "repository returns limit+1 field records"
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 1)
            def limit = 2
            def f1 = createFieldRecord("field-1", EXTRACTION_ID, "closing_date")
            def f2 = createFieldRecord("field-2", EXTRACTION_ID, "location")
            def f3 = createFieldRecord("field-3", EXTRACTION_ID, "currency") // extra
            def records = [f1, f2, f3]
            def dto1 = new ExtractionField()
            def dto2 = new ExtractionField()

        when: "listing with limit 2"
            def result = service.listExtractionFields(COMPANY_ID, EXTRACTION_ID, null, null, limit, null)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "repository returns limit+1"
            1 * extractionFieldRepository.findByExtractionId({ it.limit == 2 }) >> records

        and: "mapper only receives the first 2 records"
            1 * extractionFieldMapper.toDtoList([f1, f2], EntityType.TENDER, ENTITY_ID) >> [dto1, dto2]

        and: "result has 2 items and non-null cursor"
            with(result) {
                data.size() == 2
                pagination.cursor != null
            }
    }

    def "TC-S-F03: should pass fieldName filter to query"() {
        given: "filtering by field name"
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 1)
            def f1 = createFieldRecord("field-1", EXTRACTION_ID, "closing_date")

        when: "listing with fieldName=closing_date"
            def result = service.listExtractionFields(COMPANY_ID, EXTRACTION_ID, "closing_date", null, 20, null)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "query has fieldName=closing_date"
            1 * extractionFieldRepository.findByExtractionId({
                it.fieldName == "closing_date"
            }) >> [f1]

        and: "mapper converts"
            1 * extractionFieldMapper.toDtoList([f1], EntityType.TENDER, ENTITY_ID) >> [new ExtractionField()]

        and: "result has 1 field"
            result.data.size() == 1
    }

    def "TC-S-F04: should pass documentId filter to query as string"() {
        given: "filtering by document ID"
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 1)
            def docId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
            def f1 = createFieldRecord("field-1", EXTRACTION_ID, "location")

        when: "listing with a documentId filter"
            def result = service.listExtractionFields(COMPANY_ID, EXTRACTION_ID, null, docId, 20, null)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "query has documentId as string"
            1 * extractionFieldRepository.findByExtractionId({
                it.documentId == "dddddddd-dddd-dddd-dddd-dddddddddddd"
            }) >> [f1]

        and: "mapper converts"
            1 * extractionFieldMapper.toDtoList([f1], EntityType.TENDER, ENTITY_ID) >> [new ExtractionField()]

        and: "result has 1 field"
            result.data.size() == 1
    }

    def "TC-S-F05: should pass null documentId to query when not filtered"() {
        given: "no document ID filter"
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 1)

        when: "listing without documentId"
            service.listExtractionFields(COMPANY_ID, EXTRACTION_ID, null, null, 20, null)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "query has null documentId"
            1 * extractionFieldRepository.findByExtractionId({
                it.documentId == null
            }) >> []

        and: "empty mapper call"
            1 * extractionFieldMapper.toDtoList([], EntityType.TENDER, ENTITY_ID) >> []
    }

    def "TC-S-F06: should throw NotFoundException when extraction not found"() {
        given: "extraction does not exist"

        when: "listing fields for nonexistent extraction"
            service.listExtractionFields(COMPANY_ID, "nonexistent-ext", null, null, 20, null)

        then: "extractionRepository throws NotFoundException"
            1 * extractionRepository.findById("nonexistent-ext") >> {
                throw new NotFoundException("api.extraction.notFound", "Extraction not found.")
            }

        and: "NotFoundException propagates"
            thrown(NotFoundException)

        and: "fieldRepository never called"
            0 * extractionFieldRepository.findByExtractionId(_)
    }

    def "TC-S-F07: should return empty data with null cursor when no fields"() {
        given: "extraction exists but has no fields"
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "pending", 0)

        when: "listing fields"
            def result = service.listExtractionFields(COMPANY_ID, EXTRACTION_ID, null, null, 20, null)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "field repository returns empty"
            1 * extractionFieldRepository.findByExtractionId(_) >> []

        and: "mapper called with empty list"
            1 * extractionFieldMapper.toDtoList([], EntityType.TENDER, ENTITY_ID) >> []

        and: "result is empty with null cursor"
            with(result) {
                data.isEmpty()
                pagination.cursor == null
            }
    }

    def "TC-S-F08: should propagate NotFoundException when extraction belongs to different company"() {
        given: "extraction belongs to company 999"
            def extraction = createExtractionRecord(EXTRACTION_ID, "999", ENTITY_ID_STR, "completed", 1)

        when: "listing fields for wrong company"
            service.listExtractionFields(COMPANY_ID, EXTRACTION_ID, null, null, 20, null)

        then: "extraction found with wrong company"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "NotFoundException thrown"
            def ex = thrown(NotFoundException)
            ex.code == "api.extraction.notFound"

        and: "fieldRepository never called"
            0 * extractionFieldRepository.findByExtractionId(_)
    }

    // ==================== bulkUpdateFields ====================

    def "TC-S-B01: should update fields when If-Match matches version and return DTOs in request order"() {
        given: "a valid update request with matching ETag"
            def fieldId1 = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def fieldId2 = UUID.fromString("bbbb0002-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            def ifMatch = '"v0"'

            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 0)

            def field1 = createFieldRecord(fieldId1.toString(), EXTRACTION_ID, "closing_date")
            def field2 = createFieldRecord(fieldId2.toString(), EXTRACTION_ID, "location")

            def item1 = new ExtractionFieldUpdateItem(fieldId1, "2026-03-31")
            def item2 = new ExtractionFieldUpdateItem(fieldId2, "Vancouver")
            def request = new ExtractionFieldBulkUpdateRequest([item1, item2])

            def dto1 = new ExtractionField()
            def dto2 = new ExtractionField()

        when: "bulk updating fields"
            def result = service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, ifMatch, request)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "ownership validation fetches existing fields"
            1 * extractionFieldRepository.findAllByIds([fieldId1.toString(), fieldId2.toString()]) >> [field1, field2]

        and: "edited values serialized via jsonConverter"
            1 * jsonConverter.objectToJsonb("2026-03-31") >> JSONB.valueOf('"2026-03-31"')
            1 * jsonConverter.objectToJsonb("Vancouver") >> JSONB.valueOf('"Vancouver"')

        and: "bulkUpdateEditedValues called with updates and expected version"
            1 * extractionFieldRepository.bulkUpdateEditedValues(
                { List<FieldEditUpdate> updates ->
                    updates.size() == 2 &&
                    updates[0].fieldId() == fieldId1.toString() &&
                    updates[1].fieldId() == fieldId2.toString()
                },
                EXTRACTION_ID, 0
            ) >> new BulkUpdateResult([field1, field2], 1)

        and: "mapper converts in request order"
            1 * extractionFieldMapper.toDtoList([field1, field2], EntityType.TENDER, ENTITY_ID) >> [dto1, dto2]

        and: "response contains both updated fields"
            with(result) {
                data.size() == 2
                data[0] == dto1
                data[1] == dto2
            }
    }

    def "TC-S-B02: should throw IfMatchRequiredException when If-Match header is null"() {
        given: "a bulk update request without If-Match header"
            def fieldId = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def item = new ExtractionFieldUpdateItem(fieldId, "value")
            def request = new ExtractionFieldBulkUpdateRequest([item])

        when: "bulk updating without If-Match"
            service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, null, request)

        then: "IfMatchRequiredException thrown immediately"
            def ex = thrown(IfMatchRequiredException)
            ex.code == ApiErrorMessages.IF_MATCH_REQUIRED_CODE

        and: "no DB operations performed"
            0 * extractionRepository.findById(_)
            0 * extractionFieldRepository.findAllByIds(_)
            0 * extractionFieldRepository.bulkUpdateEditedValues(_, _, _)
    }

    def "TC-S-B03: should throw IfMatchRequiredException when If-Match header is blank"() {
        given: "a bulk update request with blank If-Match header"
            def fieldId = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def item = new ExtractionFieldUpdateItem(fieldId, "value")
            def request = new ExtractionFieldBulkUpdateRequest([item])

        when: "bulk updating with blank If-Match"
            service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, "   ", request)

        then: "IfMatchRequiredException thrown immediately"
            def ex = thrown(IfMatchRequiredException)
            ex.code == ApiErrorMessages.IF_MATCH_REQUIRED_CODE

        and: "no DB operations performed"
            0 * extractionRepository.findById(_)
    }

    def "TC-S-B04: should throw StaleVersionException when If-Match ETag is stale"() {
        given: "extraction is at version 2 but client sends version 0"
            def fieldId = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def ifMatch = '"v0"' // stale — extraction is at version 2
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 2)
            def field1 = createFieldRecord(fieldId.toString(), EXTRACTION_ID, "location")
            def item = new ExtractionFieldUpdateItem(fieldId, "Toronto")
            def request = new ExtractionFieldBulkUpdateRequest([item])

        when: "bulk updating with stale ETag"
            service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, ifMatch, request)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "ownership validated"
            1 * extractionFieldRepository.findAllByIds([fieldId.toString()]) >> [field1]

        and: "serialization called and bulkUpdateEditedValues returns 0 rows updated (stale)"
            1 * jsonConverter.objectToJsonb("Toronto") >> JSONB.valueOf('"Toronto"')
            1 * extractionFieldRepository.bulkUpdateEditedValues(_, EXTRACTION_ID, 0) >> new BulkUpdateResult([field1], 0)

        and: "StaleVersionException thrown"
            def ex = thrown(StaleVersionException)
            ex.code == ApiErrorMessages.EXTRACTION_STALE_VERSION_CODE
    }

    def "TC-S-B05: should throw NotFoundException when field does not belong to extraction"() {
        given: "one field belongs to a different extraction"
            def ownFieldId = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def alienFieldId = UUID.fromString("cccc0003-cccc-cccc-cccc-cccccccccccc")
            def ifMatch = '"v0"'
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 0)

            // ownField belongs to EXTRACTION_ID, alienField belongs to different extraction
            def ownField = createFieldRecord(ownFieldId.toString(), EXTRACTION_ID, "location")
            def alienField = createFieldRecord(alienFieldId.toString(), "different-ext", "closing_date")

            def item1 = new ExtractionFieldUpdateItem(ownFieldId, "Vancouver")
            def item2 = new ExtractionFieldUpdateItem(alienFieldId, "2026-12-31")
            def request = new ExtractionFieldBulkUpdateRequest([item1, item2])

        when: "bulk updating with field from different extraction"
            service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, ifMatch, request)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "ownership check finds both fields but one has wrong extractionId"
            1 * extractionFieldRepository.findAllByIds([ownFieldId.toString(), alienFieldId.toString()]) >> [ownField, alienField]

        and: "NotFoundException thrown for field not owned by this extraction"
            thrown(NotFoundException)

        and: "no updates applied"
            0 * extractionFieldRepository.bulkUpdateEditedValues(_, _, _)
    }

    def "TC-S-B06: should propagate NotFoundException when extraction not found"() {
        given: "extraction does not exist"
            def fieldId = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def ifMatch = '"v0"'
            def item = new ExtractionFieldUpdateItem(fieldId, "value")
            def request = new ExtractionFieldBulkUpdateRequest([item])

        when: "bulk updating for nonexistent extraction"
            service.bulkUpdateFields(COMPANY_ID, "nonexistent-ext", ifMatch, request)

        then: "extractionRepository throws NotFoundException"
            1 * extractionRepository.findById("nonexistent-ext") >> {
                throw new NotFoundException("api.extraction.notFound", "Extraction not found.")
            }

        and: "NotFoundException propagates"
            thrown(NotFoundException)

        and: "no field operations"
            0 * extractionFieldRepository.findAllByIds(_)
            0 * extractionFieldRepository.bulkUpdateEditedValues(_, _, _)
    }

    def "TC-S-B07: should throw NotFoundException when extraction belongs to different company"() {
        given: "extraction belongs to company 999"
            def fieldId = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def ifMatch = '"v0"'
            def extraction = createExtractionRecord(EXTRACTION_ID, "999", ENTITY_ID_STR, "completed", 0)
            def item = new ExtractionFieldUpdateItem(fieldId, "value")
            def request = new ExtractionFieldBulkUpdateRequest([item])

        when: "bulk updating for wrong company"
            service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, ifMatch, request)

        then: "extraction found with wrong company"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "NotFoundException thrown"
            def ex = thrown(NotFoundException)
            ex.code == "api.extraction.notFound"

        and: "no field operations"
            0 * extractionFieldRepository.findAllByIds(_)
    }

    def "TC-S-B08: should extract correct fieldId strings from request items"() {
        given: "request with 3 field updates"
            def fieldId1 = UUID.fromString("11110001-0000-0000-0000-000000000001")
            def fieldId2 = UUID.fromString("22220002-0000-0000-0000-000000000002")
            def fieldId3 = UUID.fromString("33330003-0000-0000-0000-000000000003")
            def ifMatch = '"v1"'
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 1)

            def f1 = createFieldRecord(fieldId1.toString(), EXTRACTION_ID, "location")
            def f2 = createFieldRecord(fieldId2.toString(), EXTRACTION_ID, "closing_date")
            def f3 = createFieldRecord(fieldId3.toString(), EXTRACTION_ID, "currency")

            def item1 = new ExtractionFieldUpdateItem(fieldId1, "Vancouver")
            def item2 = new ExtractionFieldUpdateItem(fieldId2, "2026-06-30")
            def item3 = new ExtractionFieldUpdateItem(fieldId3, "CAD")
            def request = new ExtractionFieldBulkUpdateRequest([item1, item2, item3])

        when: "bulk updating 3 fields"
            service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, ifMatch, request)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "fieldIds passed to findAllByIds in correct UUID string form"
            1 * extractionFieldRepository.findAllByIds([
                fieldId1.toString(),
                fieldId2.toString(),
                fieldId3.toString()
            ]) >> [f1, f2, f3]

        and: "all 3 fields serialized"
            1 * jsonConverter.objectToJsonb("Vancouver") >> JSONB.valueOf('"Vancouver"')
            1 * jsonConverter.objectToJsonb("2026-06-30") >> JSONB.valueOf('"2026-06-30"')
            1 * jsonConverter.objectToJsonb("CAD") >> JSONB.valueOf('"CAD"')

        and: "bulkUpdateEditedValues called with version=1 and returns updated records"
            1 * extractionFieldRepository.bulkUpdateEditedValues(
                { List<FieldEditUpdate> updates -> updates.size() == 3 },
                EXTRACTION_ID, 1
            ) >> new BulkUpdateResult([f1, f2, f3], 1)

        and: "mapper called with results"
            1 * extractionFieldMapper.toDtoList([f1, f2, f3], EntityType.TENDER, ENTITY_ID) >> [
                new ExtractionField(), new ExtractionField(), new ExtractionField()
            ]

        and: "result has 3 updated fields"
            noExceptionThrown()
    }

    def "TC-S-B09: should preserve request order in response [B, A, C] -> [B, A, C]"() {
        given: "a request where fields are in B, A, C order"
            def fieldIdA = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def fieldIdB = UUID.fromString("bbbb0002-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            def fieldIdC = UUID.fromString("cccc0003-cccc-cccc-cccc-cccccccccccc")
            def ifMatch = '"v0"'
            def extraction = createExtractionRecord(EXTRACTION_ID, COMPANY_ID_STR, ENTITY_ID_STR, "completed", 0)

            def fieldA = createFieldRecord(fieldIdA.toString(), EXTRACTION_ID, "location")
            def fieldB = createFieldRecord(fieldIdB.toString(), EXTRACTION_ID, "closing_date")
            def fieldC = createFieldRecord(fieldIdC.toString(), EXTRACTION_ID, "currency")

            // Request order: B, A, C
            def itemB = new ExtractionFieldUpdateItem(fieldIdB, "2026-03-31")
            def itemA = new ExtractionFieldUpdateItem(fieldIdA, "Vancouver")
            def itemC = new ExtractionFieldUpdateItem(fieldIdC, "CAD")
            def request = new ExtractionFieldBulkUpdateRequest([itemB, itemA, itemC])

            def dtoB = new ExtractionField()
            def dtoA = new ExtractionField()
            def dtoC = new ExtractionField()

        when: "bulk updating in B, A, C order"
            def result = service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, ifMatch, request)

        then: "extraction found"
            1 * extractionRepository.findById(EXTRACTION_ID) >> extraction

        and: "ownership validation — findAllByIds called with B, A, C order"
            1 * extractionFieldRepository.findAllByIds([
                fieldIdB.toString(), fieldIdA.toString(), fieldIdC.toString()
            ]) >> [fieldA, fieldB, fieldC]  // repo may return in any order

        and: "all values serialized"
            1 * jsonConverter.objectToJsonb("2026-03-31") >> JSONB.valueOf('"2026-03-31"')
            1 * jsonConverter.objectToJsonb("Vancouver") >> JSONB.valueOf('"Vancouver"')
            1 * jsonConverter.objectToJsonb("CAD") >> JSONB.valueOf('"CAD"')

        and: "bulkUpdateEditedValues returns fields in any order (A, B, C from repo)"
            1 * extractionFieldRepository.bulkUpdateEditedValues(_, EXTRACTION_ID, 0) >> new BulkUpdateResult([fieldA, fieldB, fieldC], 1)

        and: "mapper receives fields reordered to B, A, C (request order)"
            1 * extractionFieldMapper.toDtoList([fieldB, fieldA, fieldC], EntityType.TENDER, ENTITY_ID) >> [dtoB, dtoA, dtoC]

        and: "response preserves request order: B, A, C"
            result.data == [dtoB, dtoA, dtoC]
    }

    def "TC-S-B10: should propagate NotFoundException when entity (tender) not found"() {
        given: "extraction not found causes NotFoundException"
            def fieldId = UUID.fromString("aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            def ifMatch = '"v0"'
            def item = new ExtractionFieldUpdateItem(fieldId, "value")
            def request = new ExtractionFieldBulkUpdateRequest([item])

        when: "bulk updating when entity not found"
            service.bulkUpdateFields(COMPANY_ID, EXTRACTION_ID, ifMatch, request)

        then: "extractionRepository throws NotFoundException"
            1 * extractionRepository.findById(EXTRACTION_ID) >> {
                throw new NotFoundException("api.extraction.notFound", "Extraction not found.")
            }

        and: "NotFoundException propagates"
            thrown(NotFoundException)

        and: "no field operations"
            0 * extractionFieldRepository.findAllByIds(_)
            0 * extractionFieldRepository.bulkUpdateEditedValues(_, _, _)
    }

    // ==================== Helper Methods ====================

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

    private static ExtractionFieldsRecord createFieldRecord(String id, String extractionId, String fieldName) {
        def record = new ExtractionFieldsRecord()
        record.setId(id)
        record.setExtractionId(extractionId)
        record.setFieldName(fieldName)
        record.setFieldType("string")
        record.setHasConflict(false)
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }
}
