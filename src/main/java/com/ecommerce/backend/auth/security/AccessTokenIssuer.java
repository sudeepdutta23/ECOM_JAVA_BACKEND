package com.ecommerce.backend.auth.security;

import com.ecommerce.backend.common.security.TokenClaims;
import com.ecommerce.backend.common.util.UuidV7;
import com.ecommerce.backend.rbac.service.AuthorizationService;
import com.ecommerce.backend.rbac.service.UserAuthorities;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs short-lived access tokens.
 *
 * <p>Claims are kept minimal on purpose. A JWT is only Base64-encoded, not encrypted, so anyone
 * holding it can read it. It therefore carries no email or other personal data: the user ID, and
 * the user's role and permission codes (ADR-010).
 *
 * <p>Authorities are looked up here, at issue time (login, refresh, password change), so every
 * caller automatically gets current ones. One indexed query per issued token; none per request.
 */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final AuthorizationService authorization;
    private final String keyId;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties, RSAKey jwtSigningKey,
                             AuthorizationService authorization, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.authorization = authorization;
        this.keyId = jwtSigningKey.getKeyID();
        this.clock = clock;
    }

    /** @param sessionId the refresh-token family this access token belongs to ({@code sid}) */
    public AccessToken issue(UUID userId, UUID sessionId) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        UserAuthorities authorities = authorization.authoritiesOf(userId);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .id(UuidV7.generate().toString()) // jti: unique ID, lets logs reference a token safely
                .claim(TokenClaims.ROLES, List.copyOf(authorities.roles()))
                .claim(TokenClaims.PERMISSIONS, List.copyOf(authorities.permissions()))
                .claim(TokenClaims.SESSION_ID, sessionId.toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();

        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt, properties.accessTokenTtl().toSeconds());
    }
}
