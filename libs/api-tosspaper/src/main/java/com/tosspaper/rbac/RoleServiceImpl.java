package com.tosspaper.rbac;

import com.tosspaper.generated.model.RoleInfo;
import com.tosspaper.models.domain.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Service implementation for role operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;

    @Override
    public List<RoleInfo> getRoles() {
        log.debug("Fetching all available roles");
        return Arrays.stream(Role.values())
                .map(roleMapper::toGenerated)
                .toList();
    }
}

