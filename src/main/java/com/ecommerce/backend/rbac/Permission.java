package com.ecommerce.backend.rbac;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every permission the code can check. The single source of truth.
 *
 * <p>Adding a permission means: add an entry here and in {@link Permissions}, add a migration that
 * inserts the row into {@code permissions} (and usually grants it to {@code ADMIN}), and protect the
 * endpoint with {@code @PreAuthorize}. {@code PermissionCatalogVerifier} refuses to start the
 * application if the enum and the table disagree.
 */
public enum Permission {

    ROLE_READ(Permissions.ROLE_READ),
    ROLE_WRITE(Permissions.ROLE_WRITE),
    ROLE_ASSIGN(Permissions.ROLE_ASSIGN),
    USER_READ(Permissions.USER_READ),
    USER_MANAGE(Permissions.USER_MANAGE),
    USER_ERASE(Permissions.USER_ERASE),
    ORG_READ(Permissions.ORG_READ),
    ORG_MANAGE(Permissions.ORG_MANAGE),
    PROFILE_READ(Permissions.PROFILE_READ),
    CATALOG_MANAGE(Permissions.CATALOG_MANAGE);

    private static final Map<String, Permission> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Permission fromCode(String code) {
        Permission permission = BY_CODE.get(code);
        if (permission == null) {
            throw new IllegalArgumentException("Unknown permission code: " + code);
        }
        return permission;
    }
}
