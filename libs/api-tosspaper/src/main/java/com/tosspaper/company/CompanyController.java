package com.tosspaper.company;

import com.tosspaper.generated.api.CompanyApi;
import com.tosspaper.generated.model.Company;
import com.tosspaper.generated.model.CompanyCreate;
import com.tosspaper.generated.model.CompanyInfoUpdate;
import com.tosspaper.generated.model.CompanyMembership;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.tosspaper.common.security.SecurityUtils.getSubjectFromJwt;
import static com.tosspaper.common.security.SecurityUtils.getClaimFromJwt;

@RestController
@RequiredArgsConstructor
@Validated
public class CompanyController implements CompanyApi {
    private final CompanyService companyService;

    @Override
    public ResponseEntity<List<CompanyMembership>> getMyCompanies() {
        String email = getSubjectFromJwt();
        var companies = companyService.getAuthorizedCompanies(email);
        return ResponseEntity.ok(companies);
    }

    @Override
    public ResponseEntity<Company> createCompany(@Valid @RequestBody CompanyCreate companyCreate) {
        String email = getSubjectFromJwt();
        Object subClaim = getClaimFromJwt("sub");
        String userId = subClaim != null ? subClaim.toString() : null;
        var company = companyService.createCompany(companyCreate, email, userId);
        return ResponseEntity.status(201).body(company);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'company', 'companies:view')")
    public ResponseEntity<Company> getCompanyById(Long id) {
        Company company = companyService.getCompanyById(id);
        return ResponseEntity.ok(company);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'company', 'companies:edit')")
    public ResponseEntity<Void> updateCompany(Long id, @Valid @RequestBody CompanyInfoUpdate companyInfoUpdate) {
        companyService.updateCompany(id, companyInfoUpdate);
        return ResponseEntity.noContent().build();
    }
} 