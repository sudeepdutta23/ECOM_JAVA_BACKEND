package com.ecommerce.backend.org;

import java.time.Instant;
import java.util.UUID;

/** One organisation a person belongs to, as other modules see it (for example in a data export). */
public record MembershipSummary(UUID organizationId, String slug, String name, OrganizationStatus status,
                                OrgRole role, Instant joinedAt) {
}
