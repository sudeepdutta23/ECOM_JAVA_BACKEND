package com.ecommerce.backend.common.security;

import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers every 401: no token, malformed token, bad signature, wrong issuer or expired token.
 *
 * <ul>
 *   <li>An expired token gets its own code, {@code AUTH_TOKEN_EXPIRED}, so a client knows it
 *       should call {@code /refresh} instead of sending the user to the login page.</li>
 *   <li>All other failures get the same generic message. Telling an attacker "bad signature"
 *       versus "wrong issuer" would help them probe the system.</li>
 *   <li>The {@code WWW-Authenticate} header follows RFC 6750.</li>
 * </ul>
 */
public class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAuthenticationEntryPoint.class);

    private final ProblemResponseWriter writer;

    ProblemDetailsAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        boolean tokenPresented = hasBearerToken(request);
        ErrorCode code;
        String detail;
        if (isExpired(exception)) {
            code = ErrorCode.AUTH_TOKEN_EXPIRED;
            detail = "The access token has expired. Obtain a new one with the refresh endpoint.";
        } else if (tokenPresented) {
            code = ErrorCode.UNAUTHORIZED;
            detail = "The access token is invalid.";
        } else {
            code = ErrorCode.UNAUTHORIZED;
            detail = "Authentication is required to access this resource.";
        }

        // Never log the token itself; the exception type is enough to diagnose.
        log.debug("Authentication failed: code={}, path={}, cause={}",
                code, request.getRequestURI(), exception.getClass().getSimpleName());

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                tokenPresented ? "Bearer error=\"invalid_token\"" : "Bearer");
        writer.write(response, ProblemDetails.of(code, detail, request.getRequestURI()));
    }

    private static boolean hasBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    /**
     * Spring wraps the validator's error: InvalidBearerTokenException -> JwtValidationException.
     * The timestamp validator reports expiry as an OAuth2Error whose description starts with
     * "Jwt expired". This is the only place that depends on that wording, and a test covers it.
     */
    static boolean isExpired(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException validation) {
                return validation.getErrors().stream()
                        .anyMatch(e -> e.getDescription() != null && e.getDescription().startsWith("Jwt expired"));
            }
        }
        return false;
    }
}
