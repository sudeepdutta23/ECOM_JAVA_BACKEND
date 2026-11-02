package com.ecommerce.backend.org.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.org.dto.OrganizationResponse;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.service.OrganizationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(OrganizationController.class)
@Import(SecurityConfig.class)
class OrganizationControllerTest {

    private static final String ORGS = "/api/v1/organizations";
    private static final UUID CALLER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizations;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject(CALLER.toString())));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static OrganizationResponse response(UUID id) {
        return new OrganizationResponse(id, "acme-store", "Acme Store", OrganizationStatus.PENDING_APPROVAL,
                OrgRole.OWNER, List.copyOf(OrgRole.OWNER.permissions()), 0, Instant.now(), Instant.now());
    }

    @Test
    void createNormalizesTheSlugAndAnswers201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(organizations.create(CALLER, "acme-store", "Acme Store")).thenReturn(response(id));

        mockMvc.perform(signedIn(json(post(ORGS), "{\"slug\":\"  Acme-Store \",\"name\":\" Acme Store \"}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", ORGS + "/" + id))
                .andExpect(jsonPath("$.data.myRole").value("OWNER"));
    }

    @Test
    void invalidSlugsAndMissingNamesAreValidationErrors() throws Exception {
        for (String body : List.of("{\"slug\":\"-acme\",\"name\":\"Acme\"}", "{\"slug\":\"a\",\"name\":\"Acme\"}",
                "{\"slug\":\"acme store\",\"name\":\"Acme\"}", "{\"slug\":\"acme\",\"name\":\"  \"}",
                "{\"slug\":\"acme\",\"name\":\"" + "x".repeat(101) + "\"}")) {
            mockMvc.perform(signedIn(json(post(ORGS), body))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(organizations);
    }

    @Test
    void theOwnerAndStatusCannotBeSmuggledIn() throws Exception {
        mockMvc.perform(signedIn(json(post(ORGS), "{\"slug\":\"acme\",\"name\":\"Acme\",\"status\":\"ACTIVE\"}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(organizations);
    }

    @Test
    void businessConflictsKeepTheirCodes() throws Exception {
        when(organizations.create(any(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.ORG_SLUG_TAKEN, "taken"));

        mockMvc.perform(signedIn(json(post(ORGS), "{\"slug\":\"acme\",\"name\":\"Acme\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORG_SLUG_TAKEN"));
    }

    @Test
    void updateNeedsAVersionAndAcceptsNoSlug() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(signedIn(json(put(ORGS + "/" + id), "{\"name\":\"New\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        mockMvc.perform(signedIn(json(put(ORGS + "/" + id), "{\"name\":\"New\",\"version\":0,\"slug\":\"other\"}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(organizations);

        when(organizations.update(CALLER, id, "New", 3)).thenReturn(response(id));
        mockMvc.perform(signedIn(json(put(ORGS + "/" + id), "{\"name\":\"New\",\"version\":3}")))
                .andExpect(status().isOk());
        verify(organizations).update(CALLER, id, "New", 3);
    }

    @Test
    void myOrganizationsArePagedWithinTheLimits() throws Exception {
        mockMvc.perform(signedIn(get(ORGS).param("size", "101"))).andExpect(status().isBadRequest());
        verifyNoInteractions(organizations);

        mockMvc.perform(signedIn(get(ORGS))).andExpect(status().isOk());
        verify(organizations).mine(CALLER, 0, 20);
    }

    @Test
    void everythingNeedsASignedInCaller() throws Exception {
        mockMvc.perform(get(ORGS)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(ORGS), "{\"slug\":\"acme\",\"name\":\"Acme\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(organizations);
    }
}
