package com.ecommerce.backend.rbac.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.rbac.entity.PermissionDefinition;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.repository.PermissionDefinitionRepository;
import com.ecommerce.backend.rbac.repository.RoleRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PermissionCatalogVerifierTest {

    private final PermissionDefinitionRepository permissions = mock(PermissionDefinitionRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionCatalogVerifier verifier = new PermissionCatalogVerifier(permissions, roles);

    @BeforeEach
    void systemRolesExist() {
        Role systemRole = mock(Role.class);
        when(systemRole.isSystemRole()).thenReturn(true);
        when(roles.findByCode(anyString())).thenReturn(Optional.of(systemRole));
    }

    private static PermissionDefinition row(String code) {
        PermissionDefinition definition = mock(PermissionDefinition.class);
        when(definition.getCode()).thenReturn(code);
        return definition;
    }

    private static List<PermissionDefinition> rowsForEveryEnumEntry() {
        return new ArrayList<>(Arrays.stream(Permission.values()).map(p -> row(p.code())).toList());
    }

    @Test
    void passesWhenCodeAndDatabaseAgree() {
        // Build the mocks BEFORE stubbing: creating mocks inside when(...) is "unfinished stubbing".
        List<PermissionDefinition> rows = rowsForEveryEnumEntry();
        when(permissions.findAll()).thenReturn(rows);

        assertThatCode(verifier::verify).doesNotThrowAnyException();
    }

    @Test
    void failsWhenAPermissionHasNoMigration() {
        List<PermissionDefinition> rows = rowsForEveryEnumEntry();
        rows.removeIf(r -> r.getCode().equals("role:assign"));
        when(permissions.findAll()).thenReturn(rows);

        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("In code but not in database (add a migration): [role:assign]");
    }

    @Test
    void failsWhenTheDatabaseHasAnUnknownPermission() {
        List<PermissionDefinition> rows = rowsForEveryEnumEntry();
        rows.add(row("order:refund"));
        when(permissions.findAll()).thenReturn(rows);

        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in database but not in code (add to Permission): [order:refund]");
    }

    @Test
    void failsWhenASystemRoleIsMissing() {
        // Build the mocks BEFORE stubbing: creating mocks inside when(...) is "unfinished stubbing".
        List<PermissionDefinition> rows = rowsForEveryEnumEntry();
        when(permissions.findAll()).thenReturn(rows);
        when(roles.findByCode("ADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing system roles: [ADMIN]");
    }
}
