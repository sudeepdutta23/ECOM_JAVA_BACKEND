package com.ecommerce.backend.rbac.service;

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A user's roles and the union of their permissions, both sorted so tokens and responses are
 * deterministic (and diffable in tests and logs).
 */
public record UserAuthorities(SortedSet<String> roles, SortedSet<String> permissions) {

    public UserAuthorities {
        roles = new TreeSet<>(roles);
        permissions = new TreeSet<>(permissions);
    }

    public static UserAuthorities none() {
        return new UserAuthorities(new TreeSet<>(), new TreeSet<>());
    }

    public static UserAuthorities of(Set<String> roles, Set<String> permissions) {
        return new UserAuthorities(new TreeSet<>(roles), new TreeSet<>(permissions));
    }
}
