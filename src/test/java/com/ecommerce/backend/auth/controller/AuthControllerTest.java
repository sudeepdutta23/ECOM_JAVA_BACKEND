package com.ecommerce.backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.auth.security.AccessToken;
import com.ecommerce.backend.auth.security.RefreshTokenCookie;
import com.ecommerce.backend.auth.service.AuthenticationService;
import com.ecommerce.backend.auth.service.ClientInfo;
import com.ecommerce.backend.auth.service.IssuedRefreshToken;
import com.ecommerce.backend.auth.service.LoginResult;
import com.ecommerce.backend.auth.service.RegistrationService;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.security.SecurityConfig;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP contract of the registration endpoints: status codes, validation and error shape. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, RefreshTokenCookie.class})
class AuthControllerTest {

    private static final String VALID_TOKEN = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationService registration;

    @MockitoBean
    private AuthenticationService authentication;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ------------------------------------------------------------------ register

    @Test
    void registerReturns202WithGenericMessage() throws Exception {
        postJson("/api/v1/auth/register", """
                {"email":" alice@example.com ","password":"Correct-Horse-9-Battery"}""")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(AuthController.REGISTER_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(registration).register("alice@example.com", "Correct-Horse-9-Battery");
    }

    @Test
    void registerValidatesEveryField() throws Exception {
        postJson("/api/v1/auth/register", """
                {"email":"not-an-email","password":"short"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("password"));

        verifyNoInteractions(registration);
    }

    @Test
    void registerRejectsMissingFields() throws Exception {
        postJson("/api/v1/auth/register", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void registerRejectsUnknownFieldsToBlockMassAssignment() throws Exception {
        postJson("/api/v1/auth/register", """
                {"email":"alice@example.com","password":"Correct-Horse-9-Battery","status":"ACTIVE"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Unknown field 'status'"));

        verifyNoInteractions(registration);
    }

    @Test
    void registerRequiresJsonContentType() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=alice@example.com&password=Correct-Horse-9-Battery"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void passwordPolicyViolationsUseTheValidationShape() throws Exception {
        doThrow(RequestValidationException.of("password", "is too common; choose something less predictable"))
                .when(registration).register(anyString(), anyString());

        postJson("/api/v1/auth/register", """
                {"email":"alice@example.com","password":"Password1234"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void registerIsPublic() throws Exception {
        // No Authorization header at all, still accepted.
        postJson("/api/v1/auth/register", """
                {"email":"alice@example.com","password":"Correct-Horse-9-Battery"}""")
                .andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------------ login

    @Test
    void loginReturnsAccessTokenInBodyAndRefreshTokenOnlyInCookie() throws Exception {
        when(authentication.login(eq("alice@example.com"), eq("Correct-Horse-9-Battery"), any()))
                .thenReturn(new LoginResult(new AccessToken("header.payload.sig", Instant.now().plusSeconds(900), 900),
                        new IssuedRefreshToken("raw-refresh-value", Instant.now().plusSeconds(604800))));

        postJson("/api/v1/auth/login", """
                {"email":"alice@example.com","password":"Correct-Horse-9-Battery"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("header.payload.sig"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("refresh_token=raw-refresh-value"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void loginPassesClientIpAndUserAgent() throws Exception {
        when(authentication.login(anyString(), anyString(), any()))
                .thenReturn(new LoginResult(new AccessToken("t", Instant.now(), 900),
                        new IssuedRefreshToken("r", Instant.now())));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "Firefox/130")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .content("{\"email\":\"a@example.com\",\"password\":\"x\"}"))
                .andExpect(status().isOk());

        verify(authentication).login("a@example.com", "x", new ClientInfo("203.0.113.9", "Firefox/130"));
    }

    @Test
    void invalidCredentialsReturn401WithoutCookie() throws Exception {
        when(authentication.login(anyString(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid email or password."));

        postJson("/api/v1/auth/login", """
                {"email":"alice@example.com","password":"wrong"}""")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void loginDoesNotValidatePasswordStrength() throws Exception {
        // A short password must reach the service (and fail there as "invalid credentials"),
        // not be rejected by a format rule that would hint at the password policy.
        when(authentication.login(anyString(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid email or password."));

        postJson("/api/v1/auth/login", "{\"email\":\"alice@example.com\",\"password\":\"abc\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsOversizedPasswordBeforeHashing() throws Exception {
        postJson("/api/v1/auth/login", "{\"email\":\"alice@example.com\",\"password\":\"" + "x".repeat(129) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        verifyNoInteractions(authentication);
    }

    // ------------------------------------------------------------------ refresh / logout

    @Test
    void refreshReadsTheCookieAndSetsANewOne() throws Exception {
        when(authentication.refresh(eq("old-cookie-value"), any()))
                .thenReturn(new LoginResult(new AccessToken("new.jwt.sig", Instant.now().plusSeconds(900), 900),
                        new IssuedRefreshToken("new-cookie-value", Instant.now().plusSeconds(604800))));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", "old-cookie-value")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new.jwt.sig"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.startsWith("refresh_token=new-cookie-value")));
    }

    @Test
    void refreshWorksEvenWithAnExpiredAccessTokenAttached() throws Exception {
        when(authentication.refresh(any(), any()))
                .thenReturn(new LoginResult(new AccessToken("t", Instant.now(), 900), new IssuedRefreshToken("r", Instant.now())));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer expired.or.garbage")
                        .cookie(new Cookie("refresh_token", "v")))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithoutCookieIsRejected() throws Exception {
        when(authentication.refresh(org.mockito.ArgumentMatchers.isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.AUTH_INVALID_REFRESH_TOKEN, "Please sign in again."));

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesAndDeletesTheCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", "session-value")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("refresh_token=;"),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))));

        verify(authentication).logout("session-value");
    }

    @Test
    void logoutWithoutCookieStillSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutAllRequiresAnAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-all"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authentication);
    }

    @Test
    void logoutAllRevokesSessionsOfTheTokenSubject() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auth/logout-all").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        verify(authentication).logoutAll(userId);
    }

    // ------------------------------------------------------------------ verify / resend

    @Test
    void verifyEmailReturns200() throws Exception {
        postJson("/api/v1/auth/email/verify", "{\"token\":\"" + VALID_TOKEN + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(registration).verifyEmail(VALID_TOKEN);
    }

    @Test
    void verifyEmailRejectsMalformedTokenWithoutCallingTheService() throws Exception {
        postJson("/api/v1/auth/email/verify", "{\"token\":\"' OR 1=1 --\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("token"));

        verifyNoInteractions(registration);
    }

    @Test
    void resendReturns202() throws Exception {
        postJson("/api/v1/auth/email/resend-verification", "{\"email\":\"alice@example.com\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AuthController.RESEND_MESSAGE));

        verify(registration).resendVerification(any());
    }
}
