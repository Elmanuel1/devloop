package com.tosspaper.rbac;

import com.tosspaper.generated.model.AuthorizedUser;
import com.tosspaper.generated.model.RoleIdEnum;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting between AuthorizedUser domain model and generated API model.
 */
@Component
public class AuthorizedUserMapper {

    /**
     * Convert domain AuthorizedUser to generated AuthorizedUser model.
     *
     * @param domain Domain model
     * @return Generated API model
     */
    public AuthorizedUser toGenerated(com.tosspaper.models.domain.AuthorizedUser domain) {
        if (domain == null) {
            return null;
        }

        AuthorizedUser generated = new AuthorizedUser();
        generated.setId(domain.id());
        generated.setCompanyId(domain.companyId());
        generated.setUserId(domain.userId());
        generated.setEmail(domain.email());
        generated.setRoleId(RoleIdEnum.fromValue(domain.roleId()));
        generated.setRoleName(domain.roleName());
        generated.setStatus(AuthorizedUser.StatusEnum.fromValue(domain.status().getValue()));
        generated.setCreatedAt(domain.createdAt());
        generated.setUpdatedAt(domain.updatedAt());
        generated.setLastUpdatedBy(domain.lastUpdatedBy());

        return generated;
    }
}

