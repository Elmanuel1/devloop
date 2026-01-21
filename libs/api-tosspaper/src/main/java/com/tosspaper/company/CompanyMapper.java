package com.tosspaper.company;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.generated.model.Company;
import com.tosspaper.generated.model.CompanyCreate;
import com.tosspaper.generated.model.CompanyInfoUpdate;
import com.tosspaper.generated.model.CompanyMembership;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.jooq.tables.records.CompaniesRecord;
import com.tosspaper.models.utils.CountryCurrencyMapper;
import org.jooq.JSONB;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.IterableMapping;

import java.util.List;

@Mapper(
    componentModel = "spring",
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CompanyMapper {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Company DTO doesn't have metadata field, so no mapping needed
    @Mapping(target = "currency", source = "currency", qualifiedByName = "stringToCurrency")
    @Named("toCompany")
    Company toDto(CompaniesRecord record);

    // Convert to CompanyMembership with role
    @Mapping(target = "role", source = "role")
    CompanyMembership toDtoWithMembership(CompaniesRecord record, CompanyMembership.RoleEnum role);

    @IterableMapping(qualifiedByName = "toCompany")
    List<Company> toDto(List<CompaniesRecord> records);

    // Map metadata from Object to JSONB when creating a record
    @Mapping(target = "metadata", source = "companyCreate.metadata", qualifiedByName = "objectToJsonb")
    CompaniesRecord toRecord(CompanyCreate companyCreate, String email);

    /**
     * Derive currency from country_of_incorporation after mapping.
     */
    @AfterMapping
    default void deriveCurrency(@MappingTarget CompaniesRecord record) {
        if (record.getCountryOfIncorporation() != null) {
            Currency currency = CountryCurrencyMapper.mapCountryToCurrency(record.getCountryOfIncorporation());
            if (currency != null) {
                record.setCurrency(currency.getCode());
            }
        }
    }

    // Map metadata from Object to JSONB when updating a record (CompanyInfoUpdate also has metadata)
    void updateRecordFromDto(CompanyInfoUpdate dto, @MappingTarget CompaniesRecord record);

    @Named("objectToJsonb")
    default JSONB objectToJsonb(Object metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(metadata);
            return JSONB.valueOf(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert metadata to JSONB", e);
        }
    }

    @Named("toDomainModel")
    default com.tosspaper.models.domain.Company toDomainModel(CompaniesRecord record) {
        if (record == null) {
            return null;
        }
        return com.tosspaper.models.domain.Company.builder()
            .id(record.getId())
            .name(record.getName())
            .email(record.getEmail())
            .description(record.getDescription())
            .logoUrl(record.getLogoUrl())
            .termsUrl(record.getTermsUrl())
            .privacyUrl(record.getPrivacyUrl())
            .assignedEmail(record.getAssignedEmail())
            .metadata(record.getMetadata() != null ? record.getMetadata().data() : null)
            .currency(record.getCurrency() != null ? Currency.fromCode(record.getCurrency()) : null)
            .createdAt(record.getCreatedAt())
            .updatedAt(record.getUpdatedAt())
            .build();
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