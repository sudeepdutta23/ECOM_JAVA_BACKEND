package com.ecommerce.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.rbac.service.AuthorizationService;
import com.ecommerce.backend.rbac.service.UserAuthorities;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
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
    private final AuthorizationService authorization = mock(AuthorizationService.class);

    {
        when(authorization.authoritiesOf(any())).thenReturn(
                UserAuthorities.of(Set.of("ADMIN", "CUSTOMER"), Set.of("role:write", "role:read")));
    }

    private AccessTokenIssuer issuerAt(Instant instant) {
        return new AccessTokenIssuer(config.jwtEncoder(key), PROPS, key, authorization, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private JwtDecoder decoderAt(Instant instant) throws Exception {
        return config.jwtDecoder(key, PROPS, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenCarriesExpectedClaims() throws Exception {
        UUID userId = UUID.randomUUID();

        AccessToken token = issuerAt(NOW).issue(userId, UUID.randomUUID());
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
        Jwt jwt = decoderAt(NOW).decode(issuerAt(NOW).issue(UUID.randomUUID(), UUID.randomUUID()).value());

        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "iat", "nbf", "exp", "jti", "roles", "perms", "sid");
    }

    @Test
    void tokenNamesItsSession() throws Exception {
        UUID session = UUID.randomUUID();

        Jwt jwt = decoderAt(NOW).decode(issuerAt(NOW).issue(UUID.randomUUID(), session).value());

        assertThat(jwt.getClaimAsString("sid")).isEqualTo(session.toString());
    }

    @Test
    void tokenCarriesSortedRolesAndPermissions() throws Exception {
        Jwt jwt = decoderAt(NOW).decode(issuerAt(NOW).issue(UUID.randomUUID(), UUID.randomUUID()).value());

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN", "CUSTOMER");
        assertThat(jwt.getClaimAsStringList("perms")).containsExactly("role:read", "role:write");
    }

    @Test
    void userWithoutRolesGetsEmptyLists() throws Exception {
        when(authorization.authoritiesOf(any())).thenReturn(UserAuthorities.none());

        Jwt jwt = decoderAt(NOW).decode(issuerAt(NOW).issue(UUID.randomUUID(), UUID.randomUUID()).value());

        assertThat(jwt.getClaimAsStringList("roles")).isEmpty();
        assertThat(jwt.getClaimAsStringList("perms")).isEmpty();
    }

    @Test
    void eachTokenHasAUniqueId() throws Exception {
        AccessTokenIssuer issuer = issuerAt(NOW);
        UUID userId = UUID.randomUUID();

        String first = decoderAt(NOW).decode(issuer.issue(userId, UUID.randomUUID()).value()).getId();
        String second = decoderAt(NOW).decode(issuer.issue(userId, UUID.randomUUID()).value()).getId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tokenIsAcceptedWithinClockSkewAfterExpiry() throws Exception {
        AccessToken token = issuerAt(NOW).issue(UUID.randomUUID(), UUID.randomUUID());

        Instant justAfterExpiry = token.expiresAt().plusSeconds(20); // skew is 30 s
        assertThat(decoderAt(justAfterExpiry).decode(token.value())).isNotNull();
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        AccessToken token = issuerAt(NOW).issue(UUID.randomUUID(), UUID.randomUUID());
        JwtDecoder later = decoderAt(token.expiresAt().plusSeconds(31));

        assertThatThrownBy(() -> later.decode(token.value()))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        RSAKey otherKey = RsaKeyLoader.generate();
        AccessTokenIssuer attacker = new AccessTokenIssuer(config.jwtEncoder(otherKey), PROPS, otherKey,
                authorization, Clock.fixed(NOW, ZoneOffset.UTC));
        String forged = attacker.issue(UUID.randomUUID(), UUID.randomUUID()).value();
        JwtDecoder decoder = decoderAt(NOW);

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void toStringHidesTheTokenValue() {
        AccessToken token = issuerAt(NOW).issue(UUID.randomUUID(), UUID.randomUUID());

        assertThat(token.toString()).doesNotContain(token.value());
    }
}
