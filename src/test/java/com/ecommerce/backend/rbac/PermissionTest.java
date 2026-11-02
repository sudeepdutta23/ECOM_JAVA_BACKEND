package com.ecommerce.backend.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PermissionTest {

    @Test
    void everyCodeFollowsTheDatabaseFormat() {
        for (Permission permission : Permission.values()) {
            // Same rule as ck_permissions_code_format in V3.
            assertThat(permission.code()).matches("^[a-z]+(:[a-z]+)+$");
        }
    }

    @Test
    void constantsAndEnumListExactlyTheSameCodes() throws IllegalAccessException {
        Set<String> constants = new java.util.TreeSet<>();
        for (Field field : Permissions.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }
        Set<String> enumCodes = Arrays.stream(Permission.values()).map(Permission::code)
                .collect(Collectors.toCollection(java.util.TreeSet::new));

        assertThat(constants).isEqualTo(enumCodes);
    }

    @Test
    void lookupByCode() {
        assertThat(Permission.fromCode("role:read")).isEqualTo(Permission.ROLE_READ);
        assertThatThrownBy(() -> Permission.fromCode("order:refund")).isInstanceOf(IllegalArgumentException.class);
    }
}
