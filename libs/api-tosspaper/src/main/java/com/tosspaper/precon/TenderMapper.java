package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderAddress;
import com.tosspaper.generated.model.TenderBond;
import com.tosspaper.generated.model.TenderCondition;
import com.tosspaper.generated.model.TenderCreateRequest;
import com.tosspaper.generated.model.TenderEvent;
import com.tosspaper.generated.model.TenderParty;
import com.tosspaper.generated.model.TenderStatus;
import com.tosspaper.generated.model.TenderUpdateRequest;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import org.jooq.JSONB;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface TenderMapper {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    // ---- Record → DTO ----

    @Mapping(target = "id", source = "id", qualifiedByName = "stringToUuid")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToStatus")
    @Mapping(target = "bonds", source = "bonds", qualifiedByName = "jsonbToBondList")
    @Mapping(target = "conditions", source = "conditions", qualifiedByName = "jsonbToConditionList")
    @Mapping(target = "parties", source = "parties", qualifiedByName = "jsonbToPartyList")
    @Mapping(target = "location", source = "location", qualifiedByName = "jsonbToAddress")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "jsonbToMap")
    @Mapping(target = "events", source = "events", qualifiedByName = "jsonbToEventList")
    Tender toDto(TendersRecord record);

    List<Tender> toDtoList(List<TendersRecord> records);

    // ---- CreateRequest → Record ----

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID().toString())")
    @Mapping(target = "status", constant = "draft")
    @Mapping(target = "companyId", source = "companyId")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "name", source = "request.name")
    @Mapping(target = "platform", source = "request.platform")
    @Mapping(target = "currency", source = "request.currency")
    @Mapping(target = "closingDate", source = "request.closingDate")
    @Mapping(target = "deliveryMethod", source = "request.deliveryMethod")
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "referenceNumber", ignore = true)
    @Mapping(target = "scopeOfWork", ignore = true)
    @Mapping(target = "siteVisitDate", ignore = true)
    @Mapping(target = "siteVisitMandatory", ignore = true)
    @Mapping(target = "completionDate", ignore = true)
    @Mapping(target = "inquiryDeadline", ignore = true)
    @Mapping(target = "submissionMethod", ignore = true)
    @Mapping(target = "submissionUrl", ignore = true)
    @Mapping(target = "liquidatedDamages", ignore = true)
    @Mapping(target = "bonds", ignore = true)
    @Mapping(target = "conditions", ignore = true)
    @Mapping(target = "parties", ignore = true)
    @Mapping(target = "location", ignore = true)
    @Mapping(target = "metadata", ignore = true)
    @Mapping(target = "events", ignore = true)
    @Mapping(target = "startDate", ignore = true)
    TendersRecord toRecord(TenderCreateRequest request, String companyId, String createdBy);

    // ---- UpdateRequest → Record (partial update) ----

    @Mapping(target = "status", source = "status", qualifiedByName = "statusToString")
    @Mapping(target = "bonds", source = "bonds", qualifiedByName = "bondListToJsonb")
    @Mapping(target = "conditions", source = "conditions", qualifiedByName = "conditionListToJsonb")
    @Mapping(target = "parties", source = "parties", qualifiedByName = "partyListToJsonb")
    @Mapping(target = "location", source = "location", qualifiedByName = "addressToJsonb")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "objectToJsonb")
    @Mapping(target = "events", source = "events", qualifiedByName = "eventListToJsonb")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "companyId", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "siteVisitDate", ignore = true)
    @Mapping(target = "siteVisitMandatory", ignore = true)
    void updateRecord(TenderUpdateRequest request, @MappingTarget TendersRecord record);

    // ---- Named converters: Record → DTO ----

    @Named("stringToUuid")
    default UUID stringToUuid(String id) {
        return id != null ? UUID.fromString(id) : null;
    }

    @Named("stringToStatus")
    default TenderStatus stringToStatus(String status) {
        return status != null ? TenderStatus.fromValue(status) : null;
    }

    @Named("jsonbToBondList")
    default List<TenderBond> jsonbToBondList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToConditionList")
    default List<TenderCondition> jsonbToConditionList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToPartyList")
    default List<TenderParty> jsonbToPartyList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToAddress")
    default TenderAddress jsonbToAddress(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), TenderAddress.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToMap")
    default Map<String, Object> jsonbToMap(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToEventList")
    default List<TenderEvent> jsonbToEventList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Named converters: DTO → Record (serializers) ----

    @Named("statusToString")
    default String statusToString(TenderStatus status) {
        return status == null ? null : status.getValue();
    }

    @Named("bondListToJsonb")
    default JSONB bondListToJsonb(List<TenderBond> bonds) {
        if (bonds == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(bonds));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize bonds", e);
        }
    }

    @Named("conditionListToJsonb")
    default JSONB conditionListToJsonb(List<TenderCondition> conditions) {
        if (conditions == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(conditions));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize conditions", e);
        }
    }

    @Named("partyListToJsonb")
    default JSONB partyListToJsonb(List<TenderParty> parties) {
        if (parties == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(parties));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize parties", e);
        }
    }

    @Named("addressToJsonb")
    default JSONB addressToJsonb(TenderAddress address) {
        if (address == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(address));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize location", e);
        }
    }

    @Named("objectToJsonb")
    default JSONB objectToJsonb(Object obj) {
        if (obj == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(obj));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize metadata", e);
        }
    }

    @Named("eventListToJsonb")
    default JSONB eventListToJsonb(List<TenderEvent> events) {
        if (events == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(events));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize events", e);
        }
    }
}
