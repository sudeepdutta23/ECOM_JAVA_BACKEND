package com.ecommerce.backend.profile.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.profile.PersonalProfile;
import com.ecommerce.backend.profile.service.ProfileReader;
import com.ecommerce.backend.rbac.Permissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileAdminController.class)
@Import(SecurityConfig.class)
class ProfileAdminControllerTest {

    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final String PATH = "/api/v1/admin/users/" + CUSTOMER + "/profile";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileReader profiles;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void staffWithProfileReadSeeTheCustomersData() throws Exception {
        when(profiles.readForSupport(STAFF, CUSTOMER))
                .thenReturn(new PersonalProfile("Ada", null, null, null, null, null, List.of()));

        mockMvc.perform(get(PATH).with(jwt().jwt(j -> j.subject(STAFF.toString()))
                        .authorities(new SimpleGrantedAuthority(Permissions.PROFILE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.givenName").value("Ada"));
    }

    @Test
    void userReadAloneDoesNotOpenPersonalData() throws Exception {
        mockMvc.perform(get(PATH).with(jwt().jwt(j -> j.subject(STAFF.toString()))
                        .authorities(new SimpleGrantedAuthority(Permissions.USER_READ))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }
}
