package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderAddress;
import com.tosspaper.generated.model.TenderBond;
import com.tosspaper.generated.model.TenderCondition;
import com.tosspaper.generated.model.TenderEvent;
import com.tosspaper.generated.model.TenderParty;
import com.tosspaper.generated.model.TenderStatus;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import org.jooq.JSONB;
import org.mapstruct.AfterMapping;
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

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mapping(target = "id", source = "id", qualifiedByName = "stringToUuid")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToStatus")
    @Mapping(target = "bonds", source = "bonds", qualifiedByName = "jsonbToBondList")
    @Mapping(target = "conditions", source = "conditions", qualifiedByName = "jsonbToConditionList")
    @Mapping(target = "parties", source = "parties", qualifiedByName = "jsonbToPartyList")
    @Mapping(target = "location", source = "location", qualifiedByName = "jsonbToAddress")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "jsonbToMap")
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "events", ignore = true)
    @Mapping(target = "startDate", ignore = true)
    Tender toDto(TendersRecord record);

    List<Tender> toDtoList(List<TendersRecord> records);

    @AfterMapping
    default void mapRawFields(TendersRecord record, @MappingTarget Tender tender) {
        try {
            Object versionObj = record.get("version");
            if (versionObj instanceof Number) {
                tender.setVersion(((Number) versionObj).intValue());
            } else {
                tender.setVersion(0);
            }
        } catch (Exception e) {
            tender.setVersion(0);
        }

        try {
            Object eventsObj = record.get("events");
            if (eventsObj instanceof JSONB jsonb) {
                tender.setEvents(OBJECT_MAPPER.readValue(jsonb.data(),
                        new TypeReference<List<TenderEvent>>() {}));
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            Object startDateObj = record.get("start_date");
            if (startDateObj instanceof java.time.LocalDate localDate) {
                tender.setStartDate(localDate);
            }
        } catch (Exception e) {
            // ignore
        }
    }

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
}
