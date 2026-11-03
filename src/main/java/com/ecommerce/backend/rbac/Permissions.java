package com.ecommerce.backend.rbac;

/**
 * Permission codes as compile-time constants, for use inside annotations:
 *
 * <pre>{@code @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")}</pre>
 *
 * <p>Annotation values must be constants, so {@link Permission#code()} cannot be used there.
 * Each constant has exactly one matching {@link Permission} entry; a unit test enforces it.
 */
public final class Permissions {

    public static final String ROLE_READ = "role:read";
    public static final String ROLE_WRITE = "role:write";
    public static final String ROLE_ASSIGN = "role:assign";
    public static final String USER_READ = "user:read";
    public static final String USER_MANAGE = "user:manage";
    public static final String USER_ERASE = "user:erase";
    public static final String ORG_READ = "org:read";
    public static final String ORG_MANAGE = "org:manage";
    public static final String PROFILE_READ = "profile:read";

    private Permissions() {
    }
}
