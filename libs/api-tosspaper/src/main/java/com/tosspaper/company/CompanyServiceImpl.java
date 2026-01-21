package com.tosspaper.company;


import com.tosspaper.generated.model.Company;
import com.tosspaper.generated.model.CompanyCreate;
import com.tosspaper.generated.model.CompanyInfoUpdate;
import com.tosspaper.generated.model.CompanyMembership;
import com.tosspaper.models.config.AppEmailProperties;
import com.tosspaper.models.domain.AuthorizedUser;
import com.tosspaper.models.domain.Role;
import com.tosspaper.rbac.AuthorizedUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;



@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyServiceImpl implements CompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyMapper companyMapper;
    private final AuthorizedUserRepository authorizedUserRepository;
    private final DSLContext dslContext;
    private final AppEmailProperties appEmailProperties;

    @Override
    public List<CompanyMembership> getAuthorizedCompanies(String email) {
        // Single JOIN query to fetch companies with roles (status='enabled' filter in query)
        List<CompanyWithRole> companiesWithRoles = companyRepository.findAuthorizedCompaniesByEmail(email);

        if (companiesWithRoles.isEmpty()) {
            log.debug("User {} has no authorized companies", email);
            return Collections.emptyList();
        }

        // Map to CompanyMembership with role
        return companiesWithRoles.stream()
                .map(cr -> {
                    CompanyMembership.RoleEnum roleEnum = CompanyMembership.RoleEnum.fromValue(cr.roleId());
                    return companyMapper.toDtoWithMembership(cr.company(), roleEnum);
                })
                .toList();
    }

    @Override
    public Company createCompany(CompanyCreate companyCreate, String email, String userId) {
        String assignedEmail = companyCreate.getAssignedEmail();
        if (assignedEmail == null || assignedEmail.isBlank()) {
            assignedEmail = generateAssignedEmail(email);
        }

        CompanyCreate createWithAssignedEmail = companyCreate.assignedEmail(assignedEmail);

        String resolvedUserId = (userId != null && !userId.isBlank()) ? userId : email;

        // Map outside transaction - only DB writes in transaction
        var record = companyMapper.toRecord(createWithAssignedEmail, email);
        var savedRecord = dslContext.transactionResult(configuration -> {
            DSLContext dsl = configuration.dsl();
            var txSavedRecord = companyRepository.save(dsl, record);

            AuthorizedUser owner = AuthorizedUser.builder()
                    .id(UUID.randomUUID().toString())
                    .companyId(txSavedRecord.getId())
                    .userId(resolvedUserId)
                    .email(email)
                    .roleId(Role.OWNER.getId())
                    .roleName(Role.OWNER.getDisplayName())
                    .status(AuthorizedUser.UserStatus.ENABLED)
                    .build();

            authorizedUserRepository.save(dsl, owner);

            return txSavedRecord;
        });

        return companyMapper.toDto(savedRecord);
    }

    @Override
    public Company getCompanyById(Long id) {
        var companyRecord = companyRepository.findById(id);
        return companyMapper.toDto(companyRecord);
    }

    @Override
    public Company updateCompany(Long id, CompanyInfoUpdate companyUpdate)  {
        var existingRecord = companyRepository.findById(id);
        companyMapper.updateRecordFromDto(companyUpdate, existingRecord);
        var updatedRecord = companyRepository.update(existingRecord);
        return companyMapper.toDto(updatedRecord);
    }

    /**
     * Generate assigned email for company in format: {user-domain}+{random6}@{allowedDomain}
     * Domain is configured in app.email.allowed-domain
     */
    private String generateAssignedEmail(String userEmail) {
        Random random = new Random();
        int randomDigits = 100000 + random.nextInt(900000); // 6-digit number (100000-999999)

        // Extract domain from user email (e.g., "acme.com" from "john@acme.com")
        String userDomain = userEmail.substring(userEmail.lastIndexOf("@") + 1);

        // Remove TLD from domain (e.g., "acme.com" -> "acme")
        String domainPrefix = userDomain.contains(".")
                ? userDomain.substring(0, userDomain.lastIndexOf("."))
                : userDomain;

        // Generate: {domain-prefix}+{random6}@{allowedDomain}
        return domainPrefix + "+" + randomDigits + "@" + appEmailProperties.getAllowedDomain();
    }

} 