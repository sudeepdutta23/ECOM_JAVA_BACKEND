package com.ecommerce.backend.auth.security;

import com.ecommerce.backend.common.util.UuidV7;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Instant;
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
 * holding it can read it. It therefore carries no email or other personal data, just the user ID.
 * Roles arrive as a claim in Phase 2.
 */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final String keyId;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties, RSAKey jwtSigningKey, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.keyId = jwtSigningKey.getKeyID();
        this.clock = clock;
    }

    public AccessToken issue(UUID userId) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .id(UuidV7.generate().toString()) // jti: unique ID, lets logs reference a token safely
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();

        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt, properties.accessTokenTtl().toSeconds());
    }
}
