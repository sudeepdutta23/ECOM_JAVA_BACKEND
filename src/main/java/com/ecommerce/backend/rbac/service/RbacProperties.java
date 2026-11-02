package com.ecommerce.backend.rbac.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RBAC settings, bound from {@code app.rbac.*}.
 *
 * @param bootstrapAdminEmail when set, at startup this account becomes ADMIN if no active
 *                            administrator exists yet; empty disables the bootstrap
 */
@ConfigurationProperties(prefix = "app.rbac")
public record RbacProperties(String bootstrapAdminEmail) {
}
