package com.ecommerce.backend.rbac.service;

import com.ecommerce.backend.rbac.entity.PermissionDefinition;
import com.ecommerce.backend.rbac.repository.PermissionDefinitionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The permission catalogue, read-only. Thin, but it keeps one rule without exceptions:
 * controllers never use repositories (enforced by {@code ModuleBoundariesTest}).
 */
@Service
public class PermissionCatalogService {

    private final PermissionDefinitionRepository permissions;

    public PermissionCatalogService(PermissionDefinitionRepository permissions) {
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<PermissionDefinition> list() {
        return permissions.findAllByOrderByCodeAsc();
    }
}
