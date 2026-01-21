package com.tosspaper.rbac;

import com.tosspaper.generated.model.CompanyInvitation;
import com.tosspaper.generated.model.RoleIdEnum;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting between CompanyInvitation domain model and generated API model.
 */
@Component
public class InvitationMapper {

    /**
     * Convert domain CompanyInvitation to generated CompanyInvitation model.
     *
     * @param domain Domain model
     * @return Generated API model
     */
    public CompanyInvitation toGenerated(com.tosspaper.models.domain.CompanyInvitation domain) {
        if (domain == null) {
            return null;
        }

        CompanyInvitation generated = new CompanyInvitation();
        generated.setCompanyId(domain.companyId());
        generated.setEmail(domain.email());
        generated.setRoleId(RoleIdEnum.fromValue(domain.roleId()));
        generated.setRoleName(domain.roleName());
        generated.setStatus(CompanyInvitation.StatusEnum.fromValue(domain.status().getValue()));
        generated.setCreatedAt(domain.createdAt());
        generated.setUpdatedAt(domain.updatedAt());
        generated.setExpiresAt(domain.expiresAt());

        return generated;
    }
}

