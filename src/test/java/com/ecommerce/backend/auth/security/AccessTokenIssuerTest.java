package com.ecommerce.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** Issues tokens with the real Nimbus encoder and verifies them with the real decoder. */
class AccessTokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final JwtProperties PROPS = new JwtProperties(
            "test-issuer", Duration.ofMinutes(15), Duration.ofSeconds(30), null, null, true);

    private final RSAKey key = RsaKeyLoader.generate();
    private final JwtConfig config = new JwtConfig();

    private AccessTokenIssuer issuerAt(Instant instant) {
        return new AccessTokenIssuer(config.jwtEncoder(key), PROPS, key, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private JwtDecoder decoderAt(Instant instant) throws Exception {
        return config.jwtDecoder(key, PROPS, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenCarriesExpectedClaims() throws Exception {
        UUID userId = UUID.randomUUID();

        AccessToken token = issuerAt(NOW).issue(userId);
        Jwt jwt = decoderAt(NOW).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("test-issuer");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID());
        assertThat(jwt.getHeaders().get("alg")).hasToString("RS256");
        assertThat(token.expiresIn()).isEqualTo(900);
    }

    @Test
    void tokenCarriesNoPersonalData() throws Exception {
        Jwt jwt = decoderAt(NOW).decode(issuerAt(NOW).issue(UUID.randomUUID()).value());

        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "iat", "nbf", "exp", "jti");
    }

    @Test
    void eachTokenHasAUniqueId() throws Exception {
        AccessTokenIssuer issuer = issuerAt(NOW);
        UUID userId = UUID.randomUUID();

        String first = decoderAt(NOW).decode(issuer.issue(userId).value()).getId();
        String second = decoderAt(NOW).decode(issuer.issue(userId).value()).getId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tokenIsAcceptedWithinClockSkewAfterExpiry() throws Exception {
        AccessToken token = issuerAt(NOW).issue(UUID.randomUUID());

        Instant justAfterExpiry = token.expiresAt().plusSeconds(20); // skew is 30 s
        assertThat(decoderAt(justAfterExpiry).decode(token.value())).isNotNull();
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        AccessToken token = issuerAt(NOW).issue(UUID.randomUUID());
        JwtDecoder later = decoderAt(token.expiresAt().plusSeconds(31));

        assertThatThrownBy(() -> later.decode(token.value()))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        RSAKey otherKey = RsaKeyLoader.generate();
        AccessTokenIssuer attacker = new AccessTokenIssuer(config.jwtEncoder(otherKey), PROPS, otherKey,
                Clock.fixed(NOW, ZoneOffset.UTC));
        String forged = attacker.issue(UUID.randomUUID()).value();
        JwtDecoder decoder = decoderAt(NOW);

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void toStringHidesTheTokenValue() {
        AccessToken token = issuerAt(NOW).issue(UUID.randomUUID());

        assertThat(token.toString()).doesNotContain(token.value());
    }
}
