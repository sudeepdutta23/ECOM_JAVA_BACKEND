package com.ecommerce.backend.common.security;

import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers every 403: the caller is authenticated but lacks permission.
 * Becomes important in Phase 2 when roles and permissions arrive.
 */
public class ProblemDetailsAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAccessDeniedHandler.class);

    private final ProblemResponseWriter writer;

    ProblemDetailsAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        log.info("Access denied: path={}", request.getRequestURI());
        writer.write(response, ProblemDetails.of(ErrorCode.FORBIDDEN,
                "You do not have permission to perform this action.", request.getRequestURI()));
    }
}
