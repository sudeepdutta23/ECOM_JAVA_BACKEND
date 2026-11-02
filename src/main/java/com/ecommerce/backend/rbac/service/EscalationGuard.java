package com.ecommerce.backend.rbac.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.rbac.Permission;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one rule behind every escalation check: you may only act on permissions you hold yourself.
 *
 * <p>Shared by role management (what goes into a role) and role assignment (which role you hand
 * out or take away), so the two can never drift apart.
 */
final class EscalationGuard {

    private static final Logger log = LoggerFactory.getLogger(EscalationGuard.class);

    private EscalationGuard() {
    }

    /**
     * @param held   the caller's CURRENT permissions, read from the database, never from the token
     * @param action completes the sentence "You cannot ... with permissions you do not hold"
     */
    static void requireWithin(UUID callerId, UserAuthorities held, Collection<Permission> permissions, String action) {
        Set<String> holds = held.permissions();
        Set<String> missing = permissions.stream().map(Permission::code)
                .filter(code -> !holds.contains(code))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!missing.isEmpty()) {
            log.warn("Privilege escalation blocked: callerId={}, missing={}", callerId, missing);
            throw new BusinessException(ErrorCode.RBAC_ESCALATION_DENIED,
                    "You cannot " + action + " with permissions you do not hold: " + missing);
        }
    }
}
