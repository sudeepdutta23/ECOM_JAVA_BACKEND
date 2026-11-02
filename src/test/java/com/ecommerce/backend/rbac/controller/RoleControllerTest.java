package com.ecommerce.backend.rbac.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.service.RoleManagementService;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(RoleController.class)
@Import(SecurityConfig.class)
class RoleControllerTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String VALID_CREATE = """
            {"code":"ORDER_MANAGER","name":"Order manager","permissions":["role:read"]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleManagementService roles;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... authorities) {
        return request.with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()))
                .authorities(java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new)
                        .toArray(org.springframework.security.core.GrantedAuthority[]::new)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static Role role(String code) {
        Role role = Role.custom(code, "Order manager", null, EnumSet.of(Permission.ROLE_READ));
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(role, "version", 0L);
        return role;
    }

    @Test
    void createReturns201WithLocationAndUsesTheTokenSubjectAsCaller() throws Exception {
        Role created = role("ORDER_MANAGER");
        when(roles.create(eq(ADMIN_ID), eq("ORDER_MANAGER"), any())).thenReturn(created);

        mockMvc.perform(as(json(post("/api/v1/admin/roles"), VALID_CREATE), "role:write"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/roles/" + created.getId()))
                .andExpect(jsonPath("$.data.code").value("ORDER_MANAGER"))
                .andExpect(jsonPath("$.data.permissions[0]").value("role:read"))
                .andExpect(jsonPath("$.data.systemRole").value(false))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void createNeedsRoleWrite() throws Exception {
        mockMvc.perform(as(json(post("/api/v1/admin/roles"), VALID_CREATE), "role:read"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(roles);
    }

    @Test
    void createValidatesCodeNameAndPermissions() throws Exception {
        mockMvc.perform(as(json(post("/api/v1/admin/roles"), "{\"code\":\"order-manager\",\"name\":\" \"}"), "role:write"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3)); // code, name, permissions

        verifyNoInteractions(roles);
    }

    @Test
    void updateRequiresAVersion() throws Exception {
        mockMvc.perform(as(json(put("/api/v1/admin/roles/" + UUID.randomUUID()),
                        "{\"name\":\"N\",\"permissions\":[]}"), "role:write"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void updateRejectsChangingTheCode() throws Exception {
        mockMvc.perform(as(json(put("/api/v1/admin/roles/" + UUID.randomUUID()),
                        "{\"code\":\"NEW\",\"name\":\"N\",\"permissions\":[],\"version\":0}"), "role:write"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown field 'code'"));
    }

    @Test
    void updatePassesTheVersionThrough() throws Exception {
        UUID roleId = UUID.randomUUID();
        when(roles.update(eq(ADMIN_ID), eq(roleId), any(), anyLong())).thenReturn(role("ORDER_MANAGER"));

        mockMvc.perform(as(json(put("/api/v1/admin/roles/" + roleId),
                        "{\"name\":\"N\",\"permissions\":[\"role:read\"],\"version\":7}"), "role:write"))
                .andExpect(status().isOk());

        verify(roles).update(eq(ADMIN_ID), eq(roleId), any(), eq(7L));
    }

    @Test
    void listAndGetNeedOnlyRoleRead() throws Exception {
        when(roles.list()).thenReturn(java.util.List.of(role("ORDER_MANAGER")));

        mockMvc.perform(as(get("/api/v1/admin/roles"), "role:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("ORDER_MANAGER"));
    }

    @Test
    void unknownRoleIs404Problem() throws Exception {
        UUID roleId = UUID.randomUUID();
        when(roles.get(roleId)).thenThrow(new ResourceNotFoundException("Role"));

        mockMvc.perform(as(get("/api/v1/admin/roles/" + roleId), "role:read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void malformedRoleIdIs400NotA500() throws Exception {
        mockMvc.perform(as(get("/api/v1/admin/roles/not-a-uuid"), "role:read"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID roleId = UUID.randomUUID();

        mockMvc.perform(as(delete("/api/v1/admin/roles/" + roleId), "role:write"))
                .andExpect(status().isNoContent());

        verify(roles).delete(ADMIN_ID, roleId);
    }
}
