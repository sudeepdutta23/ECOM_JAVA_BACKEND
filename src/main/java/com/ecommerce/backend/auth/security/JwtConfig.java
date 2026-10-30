package com.ecommerce.backend.auth.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Wires the signing key into a {@link JwtEncoder} (issue tokens) and a {@link JwtDecoder}
 * (verify tokens). Spring Security's bearer-token filter uses the decoder automatically.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    @Bean
    public RSAKey jwtSigningKey(JwtProperties properties, ResourceLoader resourceLoader) {
        return RsaKeyLoader.load(properties, resourceLoader);
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwtSigningKey)));
    }

    /**
     * Verification rules, all mandatory:
     * <ul>
     *   <li>Signature must be RS256 with our public key. Pinning the algorithm blocks
     *       {@code alg=none} and HS256 "key confusion" attacks.</li>
     *   <li>{@code exp} and {@code nbf} must be valid, allowing a small clock skew.</li>
     *   <li>{@code iss} must equal our issuer.</li>
     * </ul>
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey jwtSigningKey, JwtProperties properties, Clock clock) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
