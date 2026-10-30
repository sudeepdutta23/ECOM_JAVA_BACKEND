package com.ecommerce.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class ProblemDetailsSecurityHandlersTest {

    // Same builder Spring Boot uses, so ProblemDetail properties are flattened like in production.
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final ProblemResponseWriter writer = new ProblemResponseWriter(mapper);
    private final ProblemDetailsAuthenticationEntryPoint entryPoint = new ProblemDetailsAuthenticationEntryPoint(writer);
    private final ProblemDetailsAccessDeniedHandler deniedHandler = new ProblemDetailsAccessDeniedHandler(writer);

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private static InvalidBearerTokenException expired() {
        var validation = new JwtValidationException("Jwt expired",
                List.of(new OAuth2Error("invalid_token", "Jwt expired at 2026-01-01T10:15:00Z", null)));
        return new InvalidBearerTokenException(validation.getMessage(), validation);
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsByteArray());
    }

    @Test
    void missingTokenReturnsPlain401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request(null), response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(body(response).get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body(response).get("instance").asText()).isEqualTo("/api/v1/orders");
    }

    @Test
    void invalidTokenReturnsGenericMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request("Bearer abc.def.ghi"), response,
                new InvalidBearerTokenException("Signed JWT rejected: Invalid signature"));

        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(body(response).get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.getContentAsString()).doesNotContain("signature");
    }

    @Test
    void expiredTokenGetsDedicatedCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request("Bearer abc.def.ghi"), response, expired());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body(response).get("code").asText()).isEqualTo("AUTH_TOKEN_EXPIRED");
    }

    @Test
    void expiryDetectionIgnoresOtherValidationErrors() {
        var wrongIssuer = new JwtValidationException("bad iss",
                List.of(new OAuth2Error("invalid_token", "The iss claim is not valid", null)));

        assertThat(ProblemDetailsAuthenticationEntryPoint.isExpired(wrongIssuer)).isFalse();
        assertThat(ProblemDetailsAuthenticationEntryPoint.isExpired(expired())).isTrue();
    }

    @Test
    void accessDeniedReturns403Problem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        deniedHandler.handle(request("Bearer x"), response, new AccessDeniedException("nope"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body(response).has("timestamp")).isTrue();
    }
}
