package com.ecommerce.backend.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.repository.UserSummary;
import com.ecommerce.backend.auth.security.AccessToken;
import com.ecommerce.backend.auth.security.RefreshTokenCookie;
import com.ecommerce.backend.auth.service.AccountService;
import com.ecommerce.backend.auth.service.IssuedRefreshToken;
import com.ecommerce.backend.auth.service.LoginResult;
import com.ecommerce.backend.auth.service.PasswordService;
import com.ecommerce.backend.common.security.SecurityConfig;
import java.time.Instant;
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

@WebMvcTest({PasswordController.class, AccountController.class})
@Import({SecurityConfig.class, RefreshTokenCookie.class})
class PasswordAndAccountControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TOKEN = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordService passwords;

    @MockitoBean
    private AccountService accounts;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject(USER_ID.toString())));
    }

    // ------------------------------------------------------------------ change

    @Test
    void changeRequiresAuthentication() throws Exception {
        mockMvc.perform(json(post("/api/v1/auth/password/change"),
                        "{\"currentPassword\":\"a\",\"newPassword\":\"Even-Better-Passphrase-42\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(passwords);
    }

    @Test
    void changeUsesTheTokenSubjectAndReturnsANewSession() throws Exception {
        when(passwords.changePassword(eq(USER_ID), eq("old"), eq("Even-Better-Passphrase-42"), any()))
                .thenReturn(new LoginResult(new AccessToken("new.jwt", Instant.now().plusSeconds(900), 900),
                        new IssuedRefreshToken("new-cookie", Instant.now().plusSeconds(60))));

        mockMvc.perform(signedIn(json(post("/api/v1/auth/password/change"),
                        "{\"currentPassword\":\"old\",\"newPassword\":\"Even-Better-Passphrase-42\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new.jwt"))
                .andExpect(header().string("Set-Cookie", startsWith("refresh_token=new-cookie")));
    }

    @Test
    void changeValidatesTheNewPasswordLength() throws Exception {
        mockMvc.perform(signedIn(json(post("/api/v1/auth/password/change"),
                        "{\"currentPassword\":\"old\",\"newPassword\":\"short\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }

    @Test
    void userIdInTheBodyIsRejectedNotTrusted() throws Exception {
        // Unknown fields are refused, so a client cannot try to target another account.
        mockMvc.perform(signedIn(json(post("/api/v1/auth/password/change"),
                        "{\"userId\":\"" + UUID.randomUUID() + "\",\"currentPassword\":\"old\","
                                + "\"newPassword\":\"Even-Better-Passphrase-42\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(passwords);
    }

    // ------------------------------------------------------------------ forgot / reset

    @Test
    void forgotIsPublicAndGeneric() throws Exception {
        mockMvc.perform(json(post("/api/v1/auth/password/forgot"), "{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(PasswordController.FORGOT_MESSAGE));

        verify(passwords).forgotPassword("alice@example.com");
    }

    @Test
    void resetIsPublicAndReturns204() throws Exception {
        mockMvc.perform(json(post("/api/v1/auth/password/reset"),
                        "{\"token\":\"" + TOKEN + "\",\"newPassword\":\"Even-Better-Passphrase-42\"}"))
                .andExpect(status().isNoContent());

        verify(passwords).resetPassword(TOKEN, "Even-Better-Passphrase-42");
    }

    @Test
    void resetRejectsMalformedToken() throws Exception {
        mockMvc.perform(json(post("/api/v1/auth/password/reset"),
                        "{\"token\":\"nope\",\"newPassword\":\"Even-Better-Passphrase-42\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("token"));
    }

    // ------------------------------------------------------------------ me

    @Test
    void meReturnsAnAllowListOfFields() throws Exception {
        Instant created = Instant.parse("2026-01-01T09:00:00Z");
        when(accounts.currentUser(USER_ID)).thenReturn(
                new UserSummary(USER_ID, "alice@example.com", UserStatus.ACTIVE, created, null, created));

        mockMvc.perform(signedIn(get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.lastLoginAt").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ deactivate

    @Test
    void deactivateClearsTheCookie() throws Exception {
        mockMvc.perform(signedIn(json(post("/api/v1/auth/me/deactivate"), "{\"password\":\"Correct-Horse-9-Battery\"}")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(accounts).deactivate(USER_ID, "Correct-Horse-9-Battery");
    }

    @Test
    void deactivateRequiresThePassword() throws Exception {
        mockMvc.perform(signedIn(json(post("/api/v1/auth/me/deactivate"), "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        verifyNoInteractions(accounts);
    }
}
