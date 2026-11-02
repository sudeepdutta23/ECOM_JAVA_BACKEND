package com.ecommerce.backend.rbac.repository;

import java.time.Instant;
import java.util.UUID;

/** Read model: one role held by a user. {@code assignedBy} is null for grants made by the system. */
public record UserRoleView(UUID roleId, String code, String name, boolean systemRole,
                           Instant assignedAt, UUID assignedBy) {
}
