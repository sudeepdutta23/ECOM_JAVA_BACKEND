package com.ecommerce.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtAuthoritiesConverterTest {

    private static Jwt token(List<String> roles, List<String> perms) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (roles != null) {
            builder.claim(TokenClaims.ROLES, roles);
        }
        if (perms != null) {
            builder.claim(TokenClaims.PERMISSIONS, perms);
        }
        return builder.build();
    }

    private static List<String> authorities(Jwt jwt) {
        AbstractAuthenticationToken auth = SecurityConfig.jwtAuthenticationConverter().convert(jwt);
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    @Test
    void permissionsAsIsAndRolesWithPrefix() {
        assertThat(authorities(token(List.of("ADMIN"), List.of("role:read", "role:write"))))
                .containsExactly("ROLE_ADMIN", "role:read", "role:write");
    }

    @Test
    void tokensWithoutTheClaimsGetNoAuthorities() {
        // e.g. a token issued before Phase 2: still authenticated, but may do nothing privileged.
        assertThat(authorities(token(null, null))).isEmpty();
    }

    @Test
    void principalNameIsTheUserId() {
        assertThat(SecurityConfig.jwtAuthenticationConverter().convert(token(List.of(), List.of())).getName())
                .isEqualTo("user-1");
    }
}
