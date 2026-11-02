package com.ecommerce.backend.org.repository;

import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
import java.time.Instant;
import java.util.UUID;

/** One of the caller's organisations with their role in it, read in one join. */
public record MembershipView(UUID organizationId, String slug, String name, OrganizationStatus status,
                             OrgRole role, Instant joinedAt) {
}
