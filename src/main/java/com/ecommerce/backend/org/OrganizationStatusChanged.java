package com.ecommerce.backend.org;

import java.util.UUID;

/**
 * Published inside the transaction that changes an organisation's status (approve, suspend,
 * reactivate, close). Listeners in other modules run synchronously in that same transaction, so
 * their reaction (for example hiding a suspended seller's products) commits or rolls back with it.
 *
 * <p>This is how org informs modules that depend on it without depending on them: the catalog
 * listens, org never imports the catalog.
 */
public record OrganizationStatusChanged(UUID organizationId, OrganizationStatus status) {
}
