package com.tosspaper.service.impl;

import com.tosspaper.company.CompanyRepository;
import com.tosspaper.company.CompanyService;
import com.tosspaper.models.service.CompanyLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Adapter that implements CompanyLookupService by delegating to CompanyRepository and CompanyService.
 * This allows other modules to access company operations without depending on api-tosspaper.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyLookupServiceAdapter implements CompanyLookupService {
    
    private final CompanyRepository companyRepository;
    
    @Override
    public CompanyBasicInfo getCompanyById(Long companyId) {
        var companyRecord = companyRepository.findById(companyId);
        return new CompanyBasicInfo(
            companyRecord.getId(), 
            companyRecord.getAssignedEmail(),
            companyRecord.getEmail(),
            companyRecord.getName()
        );
    }
    
    @Override
    public Optional<CompanyBasicInfo> getCompanyByAssignedEmail(String assignedEmail) {
        return companyRepository.findByAssignedEmail(assignedEmail)
                .map(record -> new CompanyBasicInfo(
                    record.getId(),
                    record.getAssignedEmail(),
                    record.getEmail(),
                    record.getName()
                ));
    }

    @Override
    public AutoApprovalSettings getAutoApprovalSettings(Long companyId) {
        var companyRecord = companyRepository.findById(companyId);
        return new AutoApprovalSettings(
            Boolean.TRUE.equals(companyRecord.getAutoApprovalEnabled()),
            companyRecord.getAutoApprovalThreshold(),
            companyRecord.getCurrency()
        );
    }
}

