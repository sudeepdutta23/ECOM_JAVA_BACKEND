package com.ecommerce.backend.profile.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.profile.dto.ProfileResponse;
import com.ecommerce.backend.profile.dto.UpdateProfileRequest;
import com.ecommerce.backend.profile.service.ProfileService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(ProfileController.class)
@Import(SecurityConfig.class)
class ProfileControllerTest {

    private static final String PROFILE = "/api/v1/profile";
    private static final UUID CALLER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileService profiles;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject(CALLER.toString())));
    }

    private static MockHttpServletRequestBuilder save(String body) {
        return signedIn(put(PROFILE)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void getReturnsTheCallersProfileAndOmitsWhatWasNeverGiven() throws Exception {
        when(profiles.get(CALLER)).thenReturn(ProfileResponse.EMPTY);

        mockMvc.perform(signedIn(get(PROFILE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.givenName").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());
    }

    @Test
    void saveNormalizesBeforeTheServiceSeesTheRequest() throws Exception {
        when(profiles.save(eq(CALLER), any())).thenReturn(
                new ProfileResponse("Ada", null, "+493012345678", "en-GB", "Europe/Berlin", 0L, Instant.now()));

        mockMvc.perform(save("""
                        {"givenName": "  Ada ", "familyName": "   ", "phoneNumber": "+49 (30) 123-456.78",
                         "preferredLocale": "EN-gb", "timeZone": " Europe/Berlin "}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));

        ArgumentCaptor<UpdateProfileRequest> captor = ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(profiles).save(eq(CALLER), captor.capture());
        UpdateProfileRequest request = captor.getValue();
        assertThat(request.givenName()).isEqualTo("Ada");
        assertThat(request.familyName()).as("blank means not given").isNull();
        assertThat(request.phoneNumber()).isEqualTo("+493012345678");
        assertThat(request.preferredLocale()).isEqualTo("en-GB");
        assertThat(request.timeZone()).isEqualTo("Europe/Berlin");
        assertThat(request.version()).isNull();
    }

    @Test
    void namesInAnyScriptAreAccepted() throws Exception {
        when(profiles.save(eq(CALLER), any())).thenReturn(ProfileResponse.EMPTY);

        for (String name : List.of("Zoë", "O'Brien-Smith", "李小龍", "Ελένη", "Nguyễn")) {
            mockMvc.perform(save("{\"givenName\": \"" + name + "\"}")).andExpect(status().isOk());
        }
    }

    @Test
    void invalidValuesAreValidationErrorsAndNeverReachTheService() throws Exception {
        for (String body : List.of(
                "{\"phoneNumber\": \"030 1234567\"}",          // no country code
                "{\"phoneNumber\": \"+0301234567\"}",          // country codes never start with 0
                "{\"phoneNumber\": \"+49 30 CALL-ME\"}",
                "{\"phoneNumber\": \"+1234567890123456\"}",    // 16 digits, E.164 allows 15
                "{\"preferredLocale\": \"english\"}",
                "{\"preferredLocale\": \"en_GB\"}",
                "{\"givenName\": \"" + "x".repeat(101) + "\"}",
                "{\"givenName\": \"Ada\\u0007\"}",             // control character
                "{\"familyName\": \"\\u202Eecalevol\"}",       // right-to-left override
                "{\"timeZone\": \"" + "x".repeat(65) + "\"}")) {
            mockMvc.perform(save(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(profiles);
    }

    @Test
    void anotherAccountsIdCannotBeSmuggledIn() throws Exception {
        mockMvc.perform(save("{\"givenName\": \"Ada\", \"userId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(profiles);
    }

    @Test
    void aStaleVersionIs409() throws Exception {
        when(profiles.save(eq(CALLER), any()))
                .thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "Reload"));

        mockMvc.perform(save("{\"givenName\": \"Ada\", \"version\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void aSignedOutCallerGets401() throws Exception {
        mockMvc.perform(get(PROFILE)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PROFILE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }
}
