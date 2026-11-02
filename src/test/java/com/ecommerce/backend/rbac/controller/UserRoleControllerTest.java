package com.ecommerce.backend.rbac.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.rbac.repository.UserRoleView;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(UserRoleController.class)
@Import(SecurityConfig.class)
class UserRoleControllerTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ROLE_ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/admin/users/" + USER_ID + "/roles";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleAssignmentService assignments;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... authorities) {
        return request.with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()))
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new)));
    }

    @Test
    void listsAUsersRoles() throws Exception {
        when(assignments.rolesOf(USER_ID)).thenReturn(List.of(
                new UserRoleView(ROLE_ID, "CUSTOMER", "Customer", true, Instant.parse("2026-10-31T10:00:00Z"), null)));

        mockMvc.perform(as(get(PATH), "role:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("CUSTOMER"))
                .andExpect(jsonPath("$.data[0].systemRole").value(true))
                .andExpect(jsonPath("$.data[0].assignedBy").doesNotExist()); // nulls are omitted
    }

    @Test
    void grantIs204AndPassesTheTokenSubjectAsCaller() throws Exception {
        mockMvc.perform(as(put(PATH + "/" + ROLE_ID), "role:assign"))
                .andExpect(status().isNoContent());

        verify(assignments).grant(ADMIN_ID, USER_ID, ROLE_ID);
    }

    @Test
    void revokeIs204() throws Exception {
        mockMvc.perform(as(delete(PATH + "/" + ROLE_ID), "role:assign"))
                .andExpect(status().isNoContent());

        verify(assignments).revoke(ADMIN_ID, USER_ID, ROLE_ID);
    }

    @Test
    void grantAndRevokeNeedRoleAssignNotJustWrite() throws Exception {
        mockMvc.perform(as(put(PATH + "/" + ROLE_ID), "role:read", "role:write")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete(PATH + "/" + ROLE_ID), "role:read", "role:write")).andExpect(status().isForbidden());

        verifyNoInteractions(assignments);
    }

    @Test
    void lastAdminIs409WithItsCode() throws Exception {
        doThrow(new BusinessException(ErrorCode.RBAC_LAST_ADMIN, "last"))
                .when(assignments).revoke(ADMIN_ID, USER_ID, ROLE_ID);

        mockMvc.perform(as(delete(PATH + "/" + ROLE_ID), "role:assign"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RBAC_LAST_ADMIN"));
    }

    @Test
    void selfModificationIs403WithItsCode() throws Exception {
        doThrow(new BusinessException(ErrorCode.RBAC_SELF_MODIFICATION_DENIED, "self"))
                .when(assignments).grant(ADMIN_ID, USER_ID, ROLE_ID);

        mockMvc.perform(as(put(PATH + "/" + ROLE_ID), "role:assign"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_SELF_MODIFICATION_DENIED"));
    }
}
