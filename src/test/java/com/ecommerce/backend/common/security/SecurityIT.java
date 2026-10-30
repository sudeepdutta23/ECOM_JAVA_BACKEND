package com.ecommerce.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.auth.security.AccessTokenIssuer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/**
 * Attacks the running application over real HTTP with good and bad tokens.
 *
 * <p>No protected business endpoint exists yet, so the tests call an unknown protected path:
 * 401 means security rejected the request; 404 means security accepted it and routing ran.
 */
class SecurityIT extends AbstractIntegrationTest {

    private static final String PROTECTED_PATH = "/api/v1/orders";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AccessTokenIssuer issuer;

    @Autowired
    private JwtEncoder realEncoder;

    // ------------------------------------------------------------------ public endpoints

    @Test
    void publicEndpointsNeedNoToken() {
        assertThat(rest.getForEntity("/api/v1/system/ping", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void publicEndpointsIgnoreAnExpiredToken() {
        // A client calling /ping (or later /refresh) with a stale header must not get a 401.
        ResponseEntity<String> response = get("/api/v1/system/ping", "Bearer " + expiredToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void apiDocsDescribeBearerAuthentication() {
        String spec = rest.getForObject("/v3/api-docs", String.class);

        assertThat(spec).contains("\"bearerAuth\"").contains("\"bearerFormat\":\"JWT\"");
    }

    // ------------------------------------------------------------------ protected endpoints

    @Test
    void missingTokenIsRejected() {
        ResponseEntity<String> response = get(PROTECTED_PATH, null);

        assertUnauthorized(response, "UNAUTHORIZED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void validTokenPassesSecurity() {
        String token = issuer.issue(UUID.randomUUID()).value();

        ResponseEntity<String> response = get(PROTECTED_PATH, "Bearer " + token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
    }

    @Test
    void expiredTokenIsRejectedWithDedicatedCode() {
        assertUnauthorized(get(PROTECTED_PATH, "Bearer " + expiredToken()), "AUTH_TOKEN_EXPIRED");
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = issuer.issue(UUID.randomUUID()).value().split("\\.");
        String forgedPayload = base64Url("{\"iss\":\"ecommerce-backend\",\"sub\":\"" + UUID.randomUUID()
                + "\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");

        ResponseEntity<String> response = get(PROTECTED_PATH, "Bearer " + parts[0] + "." + forgedPayload + "." + parts[2]);

        assertUnauthorized(response, "UNAUTHORIZED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey attackerKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                .keyID("attacker").build();
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(attackerKey)));

        String forged = encode(attacker, "ecommerce-backend", Instant.now(), Instant.now().plusSeconds(600));

        assertUnauthorized(get(PROTECTED_PATH, "Bearer " + forged), "UNAUTHORIZED");
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String foreign = encode(realEncoder, "some-other-service", Instant.now(), Instant.now().plusSeconds(600));

        assertUnauthorized(get(PROTECTED_PATH, "Bearer " + foreign), "UNAUTHORIZED");
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"ecommerce-backend\",\"sub\":\"" + UUID.randomUUID()
                + "\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");

        assertUnauthorized(get(PROTECTED_PATH, "Bearer " + header + "." + payload + "."), "UNAUTHORIZED");
    }

    @Test
    void garbageTokenIsRejected() {
        assertUnauthorized(get(PROTECTED_PATH, "Bearer not-a-jwt"), "UNAUTHORIZED");
    }

    // ------------------------------------------------------------------ headers and sessions

    @Test
    void responsesCarrySecurityHeadersAndNoSessionCookie() {
        ResponseEntity<String> response = get("/api/v1/system/ping", null);
        HttpHeaders headers = response.getHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(headers.get(HttpHeaders.SET_COOKIE)).isNull(); // stateless: no JSESSIONID
    }

    @Test
    void apiResponsesForbidScriptsFramesAndDeviceFeatures() {
        HttpHeaders headers = get("/api/v1/system/ping", null).getHeaders();

        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(headers.getFirst("Permissions-Policy")).contains("camera=()");
        // HSTS is only sent over HTTPS; this test server speaks plain HTTP.
        assertThat(headers.getFirst("Strict-Transport-Security")).isNull();
    }

    @Test
    void swaggerUiIsExemptFromTheStrictContentSecurityPolicy() {
        ResponseEntity<String> ui = rest.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getHeaders().getFirst("Content-Security-Policy")).isNull();
    }

    // ------------------------------------------------------------------ CORS

    @Test
    void preflightFromAllowedOriginIsAccepted() {
        ResponseEntity<String> response = preflight("http://localhost:3000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo("http://localhost:3000");
        assertThat(headers.getAccessControlAllowCredentials()).isTrue();
        assertThat(headers.getAccessControlAllowMethods()).contains(HttpMethod.POST);
        assertThat(headers.getAccessControlMaxAge()).isEqualTo(3600);
    }

    @Test
    void preflightFromUnknownOriginIsRefused() {
        ResponseEntity<String> response = preflight("https://evil.example");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void actualCrossOriginResponseExposesRequestId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:3000");
        ResponseEntity<String> response = rest.exchange("/api/v1/system/ping", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:3000");
        assertThat(response.getHeaders().getAccessControlExposeHeaders()).contains("X-Request-Id");
    }

    private ResponseEntity<String> preflight(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(java.util.List.of("Content-Type"));
        return rest.exchange("/api/v1/auth/login", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<String> get(String path, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static void assertUnauthorized(ResponseEntity<String> response, String code) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json;charset=UTF-8");
        assertThat(response.getBody()).contains("\"code\":\"" + code + "\"").contains("\"requestId\"");
    }

    private String expiredToken() {
        Instant past = Instant.now().minusSeconds(3600);
        return encode(realEncoder, "ecommerce-backend", past, past.plusSeconds(900));
    }

    private static String encode(JwtEncoder encoder, String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
