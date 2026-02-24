package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tosspaper.precon.generated.model.Address;
import com.tosspaper.precon.generated.model.Bond;
import com.tosspaper.precon.generated.model.Condition;
import com.tosspaper.precon.generated.model.Currency;
import com.tosspaper.precon.generated.model.DeliveryMethod;
import com.tosspaper.precon.generated.model.Party;
import com.tosspaper.precon.generated.model.Tender;
import com.tosspaper.precon.generated.model.TenderCreateRequest;
import com.tosspaper.precon.generated.model.TenderEvent;
import com.tosspaper.precon.generated.model.TenderStatus;
import com.tosspaper.precon.generated.model.TenderUpdateRequest;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import org.jooq.JSONB;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface TenderMapper {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    // ---- Record → DTO ----

    @Mapping(target = "id", source = "id", qualifiedByName = "stringToUuid")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToStatus")
    @Mapping(target = "currency", source = "currency", qualifiedByName = "stringToCurrency")
    @Mapping(target = "deliveryMethod", source = "deliveryMethod", qualifiedByName = "stringToDeliveryMethod")
    @Mapping(target = "createdBy", source = "createdBy", qualifiedByName = "stringToUuidSafe")
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
    @Mapping(target = "status", constant = "pending")
    @Mapping(target = "companyId", source = "companyId")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "name", source = "request.name")
    @Mapping(target = "platform", source = "request.platform")
    @Mapping(target = "currency", source = "request.currency", qualifiedByName = "currencyToString")
    @Mapping(target = "closingDate", source = "request.closingDate")
    @Mapping(target = "deliveryMethod", source = "request.deliveryMethod", qualifiedByName = "deliveryMethodToString")
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
    @Mapping(target = "currency", source = "currency", qualifiedByName = "currencyToString")
    @Mapping(target = "deliveryMethod", source = "deliveryMethod", qualifiedByName = "deliveryMethodToString")
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

    @Named("stringToUuidSafe")
    default UUID stringToUuidSafe(String id) {
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Named("stringToStatus")
    default TenderStatus stringToStatus(String status) {
        return status != null ? TenderStatus.fromValue(status) : null;
    }

    @Named("stringToCurrency")
    default Currency stringToCurrency(String currency) {
        return currency != null ? Currency.fromValue(currency) : null;
    }

    @Named("stringToDeliveryMethod")
    default DeliveryMethod stringToDeliveryMethod(String deliveryMethod) {
        return deliveryMethod != null ? DeliveryMethod.fromValue(deliveryMethod) : null;
    }

    @Named("jsonbToBondList")
    default List<Bond> jsonbToBondList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToConditionList")
    default List<Condition> jsonbToConditionList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToPartyList")
    default List<Party> jsonbToPartyList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToAddress")
    default Address jsonbToAddress(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), Address.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToMap")
    default Object jsonbToMap(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<Map<String, Object>>() {});
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

    @Named("currencyToString")
    default String currencyToString(Currency currency) {
        return currency == null ? null : currency.getValue();
    }

    @Named("deliveryMethodToString")
    default String deliveryMethodToString(DeliveryMethod deliveryMethod) {
        return deliveryMethod == null ? null : deliveryMethod.getValue();
    }

    @Named("bondListToJsonb")
    default JSONB bondListToJsonb(List<Bond> bonds) {
        if (bonds == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(bonds));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize bonds", e);
        }
    }

    @Named("conditionListToJsonb")
    default JSONB conditionListToJsonb(List<Condition> conditions) {
        if (conditions == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(conditions));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize conditions", e);
        }
    }

    @Named("partyListToJsonb")
    default JSONB partyListToJsonb(List<Party> parties) {
        if (parties == null) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(parties));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize parties", e);
        }
    }

    @Named("addressToJsonb")
    default JSONB addressToJsonb(Address address) {
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

    // ---- URI <-> String converters ----

    default URI stringToUri(String value) {
        return value != null ? URI.create(value) : null;
    }

    default String uriToString(URI value) {
        return value != null ? value.toString() : null;
    }
}
