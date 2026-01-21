package com.tosspaper.purchaseorder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;
import com.tosspaper.generated.model.PurchaseOrder;
import com.tosspaper.generated.model.PurchaseOrderCreate;
import com.tosspaper.generated.model.PurchaseOrderItem;
import com.tosspaper.generated.model.PurchaseOrderUpdate;
import com.tosspaper.generated.model.Contact;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.jooq.tables.pojos.PurchaseOrderItems;
import com.tosspaper.models.jooq.tables.records.PurchaseOrderFlatItemsRecord;
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord;
import lombok.SneakyThrows;
import org.jooq.JSONB;
import org.mapstruct.*;

import java.util.List;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PurchaseOrderMapper {

    ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Mapping(target = "id", source = "purchaseOrderId")
    @Mapping(target = "metadata", source = "poMetadata")
    @Mapping(target = "status", source = "status", qualifiedByName = "mapStatus")
    @Mapping(target = "currencyCode", source = "currencyCode")
    @Mapping(target = "vendorContact", ignore = true)
    @Mapping(target = "shipToContact", ignore = true)
    PurchaseOrder toDtoFromFlat(PurchaseOrderFlatItemsRecord flatRecord);
    
    @AfterMapping
    default void afterToDtoFromFlat(@MappingTarget PurchaseOrder purchaseOrder, PurchaseOrderFlatItemsRecord flatRecord) {
        purchaseOrder.setVendorContact(jsonbToContact(flatRecord.getVendorContact()));
        purchaseOrder.setShipToContact(jsonbToContact(flatRecord.getShipToContact()));
    }

    @Mapping(target = "id", source = "itemId")
    @Mapping(target = "metadata", source = "itemMetadata")
    @Mapping(target = "notes", source = "itemNotes")
    @Mapping(target = "unit", source = "unit")
    @Mapping(target = "unitCode", source = "unitCode")
    @Mapping(target = "itemId", source = "lineItemRefId")
    @Mapping(target = "accountId", source = "lineAccountRefId")
    PurchaseOrderItem toItemDtoFromFlat(PurchaseOrderFlatItemsRecord flatRecord);

    @Named("mapStatus")
    default com.tosspaper.generated.model.PurchaseOrderStatus mapStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return com.tosspaper.generated.model.PurchaseOrderStatus.fromValue(status.toLowerCase());
    }
    
    @Named("mapStatusToString")
    default String mapStatusToString(com.tosspaper.generated.model.PurchaseOrderStatus status) {
        if (status == null) {
            return null;
        }
        return status.getValue();
    }

    default List<PurchaseOrder> fromFlatRecords(List<PurchaseOrderFlatItemsRecord> flatRecords) {
        if (flatRecords == null || flatRecords.isEmpty()) {
            return List.of();
        }

        return flatRecords.stream()
                .collect(Collectors.groupingBy(PurchaseOrderFlatItemsRecord::getPurchaseOrderId,
                        LinkedHashMap::new,
                        Collectors.toList()))
                .values()
                .stream()
                .map(recordsForOneOrder -> {
                    PurchaseOrder order = toDtoFromFlat(recordsForOneOrder.get(0));
                    List<PurchaseOrderItem> items = recordsForOneOrder.stream()
                            .filter(record -> record.getItemId() != null)
                            .map(this::toItemDtoFromFlat)
                            .collect(Collectors.toList());
                    order.setItems(items);
                    return order;
                })
                .collect(Collectors.toList());
    }


    @Mapping(target = "items", source = "items")
    @Mapping(target = "status", source = "record.status", qualifiedByName = "mapStatus")
    @Mapping(target = "vendorContact", source = "record.vendorContact", qualifiedByName = "jsonbToContact")
    @Mapping(target = "shipToContact", source = "record.shipToContact", qualifiedByName = "jsonbToContact")
    PurchaseOrder toDto(PurchaseOrdersRecord record, List<PurchaseOrderItems> items);

    @Mapping(target = "status", source = "status", qualifiedByName = "mapStatus")
    @Mapping(target = "vendorContact", source = "record.vendorContact", qualifiedByName = "jsonbToContact")
    @Mapping(target = "shipToContact", source = "record.shipToContact", qualifiedByName = "jsonbToContact")
    PurchaseOrder toDtoWithoutItems(PurchaseOrdersRecord record);

    List<PurchaseOrder> toDtoList(List<PurchaseOrdersRecord> records);
    List<PurchaseOrderItems> toDtoItemList(List<PurchaseOrderItem> items);

    default List<PurchaseOrder> toDtoListWithoutItems(List<PurchaseOrdersRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toDtoWithoutItems)
                .collect(java.util.stream.Collectors.toList());
    }

    @AfterMapping
    default void afterToDto(@MappingTarget PurchaseOrder purchaseOrder) {
        if (purchaseOrder.getItems() == null) {
            purchaseOrder.setItems(java.util.Collections.emptyList());
        }
    }

    @Mapping(target = "status", constant = "pending")
    @Mapping(target = "vendorContact", source = "purchaseOrderCreate.vendorContact", qualifiedByName = "contactToJsonb")
    @Mapping(target = "shipToContact", source = "purchaseOrderCreate.shipToContact", qualifiedByName = "contactToJsonb")
    PurchaseOrdersRecord toRecord(Long companyId, String projectId, PurchaseOrderCreate purchaseOrderCreate);
    
    @Mapping(target = "status", source = "status", qualifiedByName = "mapStatusToString")
    @Mapping(target = "displayId", ignore = true)


    @Mapping(target = "vendorContact", source = "purchaseOrder.vendorContact", qualifiedByName = "contactToJsonb")
    @Mapping(target = "shipToContact", source = "purchaseOrder.shipToContact", qualifiedByName = "contactToJsonb")
    PurchaseOrdersRecord toRecord(PurchaseOrder purchaseOrder);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "purchaseOrderId", ignore = true)
    @Mapping(target = "expectedDeliveryDate", ignore = true)
    @Mapping(target = "deliveryStatus", ignore = true)
    @Mapping(target = "metadata", source = "metadata")
    PurchaseOrderItems toItemsPojo(PurchaseOrderItem item);

    @IterableMapping(qualifiedByName = "toItemsPojoIgnoreId")
    List<PurchaseOrderItems> toItemsPojos(List<PurchaseOrderItem> items);

    @Named("toItemsPojoIgnoreId")
    @Mapping(target = "id", ignore = true)
    default PurchaseOrderItems toItemsPojoIgnoreId(PurchaseOrderItem item) {
        return toItemsPojo(item);
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    @Mapping(target = "companyId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "changeLog", ignore = true)
    @Mapping(target = "itemsCount", ignore = true)
    @Mapping(target = "searchVector", ignore = true)
    @Mapping(target = "externalId", ignore = true)
    @Mapping(target = "externalProvider", ignore = true)
    @Mapping(target = "externalSyncedAt", ignore = true)
    @Mapping(target = "externalDocNumber", ignore = true)
    @Mapping(target = "provider", ignore = true)
    @Mapping(target = "syncedAt", ignore = true)
    @Mapping(target = "externalMetadata", ignore = true)
    @Mapping(target = "providerCreatedAt", ignore = true)
    @Mapping(target = "providerLastUpdatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "providerVersion", ignore = true)
    @Mapping(target = "lastSyncAt", ignore = true)
    @Mapping(target = "vendorContact", source = "dto.vendorContact", qualifiedByName = "contactToJsonb")
    @Mapping(target = "shipToContact", source = "dto.shipToContact", qualifiedByName = "contactToJsonb")
    @Mapping(target = "currencyCode", source = "dto.currencyCode", qualifiedByName = "currencyToString", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateRecordFromDto(PurchaseOrderUpdate dto, @MappingTarget PurchaseOrdersRecord record);

    @SneakyThrows
    default Map<String, Object> map(JSONB value) {
        if (value == null) {
            return null;
        }
        String jsonString = value.data();
        if (jsonString.trim().isEmpty()) {
            return null;
        }
        return objectMapper.readValue(jsonString, new TypeReference<>() {
        });
    }

    @SneakyThrows
    default JSONB map(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        return JSONB.jsonb(objectMapper.writeValueAsString(value));
    }

    @Named("jsonbToContact")
    @SneakyThrows
    default Contact jsonbToContact(JSONB value) {
        if (value == null) {
            return null;
        }
        String jsonString = value.data();
        if (jsonString.trim().isEmpty()) {
            return null;
        }
        return objectMapper.readValue(jsonString, Contact.class);
    }

    @Named("contactToJsonb")
    @SneakyThrows
    default JSONB contactToJsonb(Contact value) {
        if (value == null) {
            return null;
        }
        return JSONB.jsonb(objectMapper.writeValueAsString(value));
    }
    
    @Named("stringToCurrency")
    default Currency stringToCurrency(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return null;
        }
        return Currency.fromCode(currencyCode);
    }
    
    @Named("currencyToString")
    default String currencyToString(Currency currency) {
        return currency == null ? null : currency.getCode();
    }

} 