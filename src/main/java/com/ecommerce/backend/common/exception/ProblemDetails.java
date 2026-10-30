package com.ecommerce.backend.common.exception;

import com.ecommerce.backend.common.response.FieldErrorDetail;
import com.ecommerce.backend.common.web.RequestIdFilter;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.Nullable;

/**
 * Single place that builds the project's RFC 9457 error body.
 *
 * <p>Used by {@link GlobalExceptionHandler} for errors inside Spring MVC, and by the security
 * handlers for errors raised earlier in the filter chain, so every error has the same shape.
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, String path) {
        return of(code, detail, path, null);
    }

    public static ProblemDetail of(ErrorCode code, String detail, String path,
                                   @Nullable List<FieldErrorDetail> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setTitle(code.title());
        enrich(pd, code, path);
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return pd;
    }

    /** Adds type, instance, code, timestamp and requestId to an existing Problem Detail. */
    public static void enrich(ProblemDetail pd, ErrorCode code, String path) {
        pd.setType(typeOf(code));
        pd.setInstance(URI.create(path));
        pd.setProperty("code", code.name());
        pd.setProperty("timestamp", Instant.now());
        pd.setProperty("requestId", RequestIdFilter.currentRequestId());
    }

    static URI typeOf(ErrorCode code) {
        return URI.create("urn:problem-type:" + code.name().toLowerCase().replace('_', '-'));
    }
}
