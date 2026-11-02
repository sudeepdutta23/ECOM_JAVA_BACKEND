package com.ecommerce.backend.rbac.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminBootstrapRunnerTest {

    private final RoleAssignmentService assignments = mock(RoleAssignmentService.class);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void doesNothingWhenNoEmailIsConfigured(String email) {
        new AdminBootstrapRunner(new RbacProperties(email), assignments).run(null);

        verifyNoInteractions(assignments);
    }

    @Test
    void bootstrapsTheConfiguredEmail() {
        new AdminBootstrapRunner(new RbacProperties("first@example.com"), assignments).run(null);

        verify(assignments).bootstrapAdmin("first@example.com");
    }
}
