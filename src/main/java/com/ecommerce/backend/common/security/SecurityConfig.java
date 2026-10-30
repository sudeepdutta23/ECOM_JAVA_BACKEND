package com.ecommerce.backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The HTTP security rules for the whole API.
 *
 * <p><b>Default deny:</b> every request needs a valid access token unless it is listed below.
 * Forgetting to list a new endpoint therefore fails safe (401), never open.
 *
 * <p><b>Stateless:</b> no HTTP session and no JSESSIONID cookie. Each request proves itself with
 * its bearer token, so any instance behind a load balancer can serve it.
 *
 * <p><b>CSRF disabled:</b> CSRF abuses credentials a browser sends automatically. Access tokens
 * travel in the Authorization header, which a browser never adds by itself. The one cookie we
 * use (refresh token) is SameSite=Strict and path-scoped; see ADR-009.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    static final String[] PUBLIC_GET = {
            "/api/v1/system/ping",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    static final String[] PUBLIC_POST = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/email/verify",
            "/api/v1/auth/email/resend-verification",
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset"
    };

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        ProblemResponseWriter writer = new ProblemResponseWriter(objectMapper);
        var entryPoint = new ProblemDetailsAuthenticationEntryPoint(writer);
        var accessDeniedHandler = new ProblemDetailsAccessDeniedHandler(writer);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())                 // uses the corsConfigurationSource bean below
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())          // uses the JwtDecoder bean from JwtConfig
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        // Sent only on HTTPS requests; behind a TLS load balancer the prod profile's
                        // forward-headers-strategy makes requests count as secure.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        // JSON responses never need scripts, styles or frames. Swagger UI does, so it is exempt.
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(swaggerUi()),
                                new StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=()")));

        return http.build();
    }

    /**
     * Which browser origins may call the API from JavaScript.
     *
     * <ul>
     *   <li>An explicit allow-list from configuration; never {@code *}, which browsers forbid
     *       anyway together with credentials.</li>
     *   <li>{@code allowCredentials}: the SPA must send the refresh cookie to {@code /refresh}.</li>
     *   <li>Empty list (the production default until configured) means no cross-origin access.</li>
     * </ul>
     *
     * <p>CORS is a browser feature that RELAXES the same-origin policy. It is not access control:
     * curl ignores it completely. Authentication still protects every endpoint.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", HttpHeaders.RETRY_AFTER,
                RateLimitFilter.LIMIT_HEADER, RateLimitFilter.REMAINING_HEADER));
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1)); // browsers cache the preflight answer

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static RequestMatcher swaggerUi() {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(paths.matcher("/swagger-ui.html"), paths.matcher("/swagger-ui/**"));
    }

    /**
     * Ignores the Authorization header on public endpoints.
     *
     * <p>Without this, a client that still attaches its EXPIRED access token when calling
     * {@code /refresh} or {@code /login} would be rejected with 401 before reaching the endpoint
     * that is supposed to fix the problem.
     */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        RequestMatcher publicEndpoints = publicEndpoints();
        return request -> publicEndpoints.matches(request) ? null : delegate.resolve(request);
    }

    private static RequestMatcher publicEndpoints() {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> matchers = new ArrayList<>();
        for (String path : PUBLIC_GET) {
            matchers.add(paths.matcher(HttpMethod.GET, path));
        }
        for (String path : PUBLIC_POST) {
            matchers.add(paths.matcher(HttpMethod.POST, path));
        }
        return new OrRequestMatcher(matchers);
    }
}
