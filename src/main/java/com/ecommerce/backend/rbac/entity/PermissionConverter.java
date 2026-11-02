package com.ecommerce.backend.rbac.entity;

import com.ecommerce.backend.rbac.Permission;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link Permission} as its code ({@code 'role:read'}), not its enum name
 * ({@code 'ROLE_READ'}).
 *
 * <p>{@code @Enumerated(STRING)} would store the Java name, tying the database to how a constant
 * happens to be spelled in Java. The code is the stable, documented identifier.
 */
@Converter
public class PermissionConverter implements AttributeConverter<Permission, String> {

    @Override
    public String convertToDatabaseColumn(Permission permission) {
        return permission == null ? null : permission.code();
    }

    @Override
    public Permission convertToEntityAttribute(String code) {
        return code == null ? null : Permission.fromCode(code);
    }
}
