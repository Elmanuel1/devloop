package com.tosspaper.rbac;

import com.tosspaper.generated.model.RoleInfo;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting between Role domain model and generated API model.
 */
@Component
public class RoleMapper {

    /**
     * Convert domain Role to generated RoleInfo model.
     *
     * @param role Domain model
     * @return Generated API model
     */
    public RoleInfo toGenerated(com.tosspaper.models.domain.Role role) {
        if (role == null) {
            return null;
        }

        RoleInfo generated = new RoleInfo();
        generated.setId(RoleInfo.IdEnum.fromValue(role.getId()));
        generated.setDisplayName(role.getDisplayName());

        return generated;
    }
}

