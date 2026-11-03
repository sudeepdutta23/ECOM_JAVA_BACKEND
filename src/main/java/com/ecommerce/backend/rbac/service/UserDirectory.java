package com.ecommerce.backend.rbac.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the rbac module needs to know about user accounts, and nothing more.
 *
 * <p><b>Why an interface here, implemented in auth?</b> rbac must not import auth (the dependency
 * points {@code auth -> rbac}), yet assigning a role needs "does this user exist?" and the
 * last-admin rule needs "which admins are still active?". rbac declares the question; auth answers
 * it. This is dependency inversion: the arrow in the code stays {@code auth -> rbac}.
 *
 * <p>The org and profile modules ask the same questions and reuse this port
 * (docs/modules/ORGANIZATIONS.md, docs/modules/PROFILE.md).
 */
public interface UserDirectory {

    /** False for unknown and for erased accounts: an erased account can hold no roles again. */
    boolean exists(UUID userId);

    /** Whether this account can currently sign in. */
    boolean isActive(UUID userId);

    /**
     * Whether this account can currently sign in, and if it exists, a FOR SHARE lock on it until
     * the caller's transaction ends. Must run inside a transaction. Use it before writing personal
     * data about the user: an erasure running at the same time then either waits for that write
     * (and removes it), or committed first (and this returns false).
     */
    boolean lockIfActive(UUID userId);

    /** How many of the given users can currently sign in. */
    long countActive(Collection<UUID> userIds);

    /** Emails of the given accounts, for member lists and invitation checks. Unknown IDs are absent. */
    Map<UUID, String> emailsOf(Collection<UUID> userIds);

    /** The ID of the active account with this email, if any. Normalization is the implementation's job. */
    Optional<UUID> findActiveUserId(String email);
}
