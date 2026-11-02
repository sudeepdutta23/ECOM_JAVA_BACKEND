package com.ecommerce.backend.rbac.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the first-administrator bootstrap at startup when {@code app.rbac.bootstrap-admin-email}
 * is set. A missing account is logged, not fatal: the usual order is "start, register, verify,
 * restart", and refusing to start would block the very registration it waits for.
 */
@Component
class AdminBootstrapRunner implements ApplicationRunner {

    private final RbacProperties properties;
    private final RoleAssignmentService assignments;

    AdminBootstrapRunner(RbacProperties properties, RoleAssignmentService assignments) {
        this.properties = properties;
        this.assignments = assignments;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.bootstrapAdminEmail();
        if (email != null && !email.isBlank()) {
            assignments.bootstrapAdmin(email);
        }
    }
}
