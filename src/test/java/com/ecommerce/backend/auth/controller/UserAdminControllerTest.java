package com.ecommerce.backend.auth.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.auth.dto.AdminUserSummary;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.service.UserAdminService;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.common.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(UserAdminController.class)
@Import(SecurityConfig.class)
class UserAdminControllerTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAdminService users;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder asReader(MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("user:read")));
    }

    private static MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject(MANAGER.toString()))
                .authorities(new SimpleGrantedAuthority("user:read"), new SimpleGrantedAuthority("user:manage")));
    }

    private static MockHttpServletRequestBuilder suspend(UUID target, String json) {
        return post(USERS + "/" + target + "/suspend").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    // ------------------------------------------------------------------ actions

    @Test
    void suspendPassesTheCallerTheTargetAndTheReason() throws Exception {
        UUID target = UUID.randomUUID();

        mockMvc.perform(asManager(suspend(target, "{\"reason\":\"Chargeback fraud\"}")))
                .andExpect(status().isOk());

        verify(users).suspend(MANAGER, target, "Chargeback fraud");
    }

    @Test
    void suspendNeedsANonBlankReasonOfAtMost500Characters() throws Exception {
        mockMvc.perform(asManager(suspend(UUID.randomUUID(), "{\"reason\":\"  \"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mockMvc.perform(asManager(suspend(UUID.randomUUID(), "{\"reason\":\"" + "x".repeat(501) + "\"}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asManager(suspend(UUID.randomUUID(), "{}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(users);
    }

    @Test
    void actionsNeedUserManageNotJustRead() throws Exception {
        UUID target = UUID.randomUUID();

        for (String action : new String[] {"reactivate", "unlock", "sign-out"}) {
            mockMvc.perform(asReader(post(USERS + "/" + target + "/" + action))).andExpect(status().isForbidden());
        }
        mockMvc.perform(asReader(suspend(target, "{\"reason\":\"x\"}"))).andExpect(status().isForbidden());

        verifyNoInteractions(users);
    }

    @Test
    void reactivateUnlockAndSignOutNeedNoBody() throws Exception {
        UUID target = UUID.randomUUID();

        mockMvc.perform(asManager(post(USERS + "/" + target + "/reactivate"))).andExpect(status().isOk());
        mockMvc.perform(asManager(post(USERS + "/" + target + "/unlock"))).andExpect(status().isOk());
        mockMvc.perform(asManager(post(USERS + "/" + target + "/sign-out"))).andExpect(status().isOk());

        verify(users).reactivate(MANAGER, target);
        verify(users).unlock(MANAGER, target);
        verify(users).signOut(MANAGER, target);
    }

    @Test
    void eraseNeedsUserEraseNotJustManage() throws Exception {
        UUID target = UUID.randomUUID();

        mockMvc.perform(asManager(post(USERS + "/" + target + "/erase"))).andExpect(status().isForbidden());
        verifyNoInteractions(users);

        mockMvc.perform(post(USERS + "/" + target + "/erase").with(jwt().jwt(j -> j.subject(MANAGER.toString()))
                        .authorities(new SimpleGrantedAuthority("user:erase"))))
                .andExpect(status().isOk());
        verify(users).erase(MANAGER, target);
    }

    @Test
    void invalidStatusIs409WithItsCode() throws Exception {
        UUID target = UUID.randomUUID();
        when(users.reactivate(MANAGER, target))
                .thenThrow(new BusinessException(ErrorCode.USER_INVALID_STATUS, "This account is ACTIVE"));

        mockMvc.perform(asManager(post(USERS + "/" + target + "/reactivate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_INVALID_STATUS"));
    }

    @Test
    void listUsesDefaultsAndReturnsAPage() throws Exception {
        AdminUserSummary row = new AdminUserSummary(UUID.randomUUID(), "alice@example.com", UserStatus.ACTIVE,
                true, false, null, Instant.parse("2026-11-01T10:00:00Z"));
        when(users.search(null, null, 0, 20)).thenReturn(new PageResponse<>(List.of(row), 0, 20, 1, 1));

        mockMvc.perform(asReader(get(USERS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.content[0].locked").value(false))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void filtersAndPagingArePassedThrough() throws Exception {
        when(users.search(UserStatus.SUSPENDED, "Ali", 2, 50)).thenReturn(new PageResponse<>(List.of(), 2, 50, 0, 0));

        mockMvc.perform(asReader(get(USERS).param("status", "SUSPENDED").param("email", "Ali")
                        .param("page", "2").param("size", "50")))
                .andExpect(status().isOk());

        verify(users).search(UserStatus.SUSPENDED, "Ali", 2, 50);
    }

    @Test
    void sizeAbove100IsAValidationErrorNotSilentlyReduced() throws Exception {
        mockMvc.perform(asReader(get(USERS).param("size", "101")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        verifyNoInteractions(users);
    }

    @Test
    void negativePageAndZeroSizeAreBothReported() throws Exception {
        mockMvc.perform(asReader(get(USERS).param("page", "-1").param("size", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[1].field").value("size"));
    }

    @Test
    void unknownStatusNamesTheAcceptedValuesWithoutJavaTypeNames() throws Exception {
        mockMvc.perform(asReader(get(USERS).param("status", "BANNED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("status"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be one of [PENDING_VERIFICATION, ACTIVE, DEACTIVATED, SUSPENDED, ERASED]"));
    }

    @Test
    void nonNumericSizeIsAValidationError() throws Exception {
        mockMvc.perform(asReader(get(USERS).param("size", "lots")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid format"));
    }

    @Test
    void listAndDetailNeedUserRead() throws Exception {
        mockMvc.perform(get(USERS).with(jwt().authorities(new SimpleGrantedAuthority("role:read"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(USERS + "/" + UUID.randomUUID()).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(users);
    }

    @Test
    void unknownUserIs404() throws Exception {
        UUID ghost = UUID.randomUUID();
        when(users.get(ghost)).thenThrow(new ResourceNotFoundException("User"));

        mockMvc.perform(asReader(get(USERS + "/" + ghost)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void malformedUserIdIs400() throws Exception {
        mockMvc.perform(asReader(get(USERS + "/not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
    }

    @Test
    void emptyEmailFilterIsPassedAsIsForTheServiceToIgnore() throws Exception {
        when(users.search(isNull(), eq(""), anyInt(), anyInt())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(asReader(get(USERS).param("email", ""))).andExpect(status().isOk());
    }
}
