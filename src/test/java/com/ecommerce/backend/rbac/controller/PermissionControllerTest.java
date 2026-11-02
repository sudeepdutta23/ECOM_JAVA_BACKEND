package com.ecommerce.backend.rbac.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.rbac.entity.PermissionDefinition;
import com.ecommerce.backend.rbac.service.PermissionCatalogService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Proves method security is wired: the right authority opens the endpoint, anything else is 403. */
@WebMvcTest(PermissionController.class)
@Import(SecurityConfig.class)
class PermissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PermissionCatalogService permissions;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void holderOfRoleReadSeesTheCatalogue() throws Exception {
        PermissionDefinition read = Mockito.mock(PermissionDefinition.class);
        when(read.getCode()).thenReturn("role:read");
        when(read.getDescription()).thenReturn("View roles");
        when(permissions.list()).thenReturn(List.of(read));

        mockMvc.perform(get("/api/v1/admin/permissions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("role:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("role:read"))
                .andExpect(jsonPath("$.data[0].description").value("View roles"));
    }

    @Test
    void authenticatedUserWithoutThePermissionGets403Problem() throws Exception {
        mockMvc.perform(get("/api/v1/admin/permissions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").exists());

        verifyNoInteractions(permissions);
    }

    @Test
    void roleNameAloneIsNotEnough() throws Exception {
        // Code checks permissions, never role names: even ROLE_ADMIN without role:read is refused.
        mockMvc.perform(get("/api/v1/admin/permissions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCallerGets401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/permissions"))
                .andExpect(status().isUnauthorized());
    }
}
