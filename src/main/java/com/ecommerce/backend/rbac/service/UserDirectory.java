package com.ecommerce.backend.rbac.service;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * What the rbac module needs to know about user accounts, and nothing more.
 *
 * <p><b>Why an interface here, implemented in auth?</b> rbac must not import auth (the dependency
 * points {@code auth -> rbac}), yet assigning a role needs "does this user exist?" and the
 * last-admin rule needs "which admins are still active?". rbac declares the question; auth answers
 * it. This is dependency inversion: the arrow in the code stays {@code auth -> rbac}.
 */
public interface UserDirectory {

    boolean exists(UUID userId);

    /** How many of the given users can currently sign in. */
    long countActive(Collection<UUID> userIds);

    /** The ID of the active account with this email, if any. Normalization is the implementation's job. */
    Optional<UUID> findActiveUserId(String email);
}
