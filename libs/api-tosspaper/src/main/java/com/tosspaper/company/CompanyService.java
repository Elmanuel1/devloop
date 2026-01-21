package com.tosspaper.company;

import com.tosspaper.common.NotFoundException;
import com.tosspaper.generated.model.Company;
import com.tosspaper.generated.model.CompanyCreate;
import com.tosspaper.generated.model.CompanyInfoUpdate;
import com.tosspaper.generated.model.CompanyMembership;

import java.util.List;

public interface CompanyService {
    /**
     * Create a new company
     * @param companyCreate
     * @param email
     * @param userId Optional Supabase user ID. If null or empty, falls back to email
     * @return the created company
     */
    Company createCompany(CompanyCreate companyCreate, String email, String userId);

    /**
     * Get all companies where the user is an authorized member with their role
     * @param email User email
     * @return List of company memberships with role information
     */
    List<CompanyMembership> getAuthorizedCompanies(String email);

    /**
     * Get a company by id
     * @param id
     * @return the company
     */
    Company getCompanyById(Long id) throws NotFoundException;

    /**
     * Update a company
     * @param id
     * @param companyUpdate
     * @return the updated company
     */
    Company updateCompany(Long id, CompanyInfoUpdate companyUpdate) throws NotFoundException;
} 