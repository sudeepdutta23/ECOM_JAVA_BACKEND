package com.ecommerce.backend.rbac.service;

import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.rbac.entity.PermissionDefinition;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.repository.PermissionDefinitionRepository;
import com.ecommerce.backend.rbac.repository.RoleRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application if the code and the database disagree about permissions or
 * the system roles.
 *
 * <p>Typical mistakes it catches: a new {@link Permission} without its migration (every check on it
 * would quietly deny, since no role could ever hold it), or a migration renaming a code the Java
 * side still uses. Failing at startup turns a subtle production bug into an obvious deploy error.
 */
@Component
class PermissionCatalogVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionCatalogVerifier.class);
    static final List<String> REQUIRED_SYSTEM_ROLES = List.of(Role.ADMIN, Role.CUSTOMER);

    private final PermissionDefinitionRepository permissions;
    private final RoleRepository roles;

    PermissionCatalogVerifier(PermissionDefinitionRepository permissions, RoleRepository roles) {
        this.permissions = permissions;
        this.roles = roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    void verify() {
        Set<String> inCode = Arrays.stream(Permission.values()).map(Permission::code)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> inDatabase = permissions.findAll().stream().map(PermissionDefinition::getCode)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> missingInDatabase = new TreeSet<>(inCode);
        missingInDatabase.removeAll(inDatabase);
        Set<String> unknownToCode = new TreeSet<>(inDatabase);
        unknownToCode.removeAll(inCode);
        List<String> missingRoles = REQUIRED_SYSTEM_ROLES.stream()
                .filter(code -> roles.findByCode(code).filter(Role::isSystemRole).isEmpty())
                .toList();

        if (!missingInDatabase.isEmpty() || !unknownToCode.isEmpty() || !missingRoles.isEmpty()) {
            throw new IllegalStateException("Permission catalogue out of sync. "
                    + "In code but not in database (add a migration): " + missingInDatabase
                    + "; in database but not in code (add to Permission): " + unknownToCode
                    + "; missing system roles: " + missingRoles);
        }
        log.info("Permission catalogue verified: {} permissions, system roles {}", inCode.size(), REQUIRED_SYSTEM_ROLES);
    }
}
