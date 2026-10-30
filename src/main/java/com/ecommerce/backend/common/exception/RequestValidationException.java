package com.ecommerce.backend.common.exception;

import com.ecommerce.backend.common.response.FieldErrorDetail;
import java.util.List;

/**
 * A validation failure detected in the service layer, for rules that annotations cannot
 * express (for example "password must not equal the email").
 *
 * <p>Rendered exactly like {@code @Valid} failures: 400 with {@code VALIDATION_FAILED} and an
 * {@code errors} list, so clients handle both the same way.
 */
public class RequestValidationException extends BusinessException {

    private final transient List<FieldErrorDetail> errors;

    public RequestValidationException(List<FieldErrorDetail> errors) {
        super(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid");
        this.errors = List.copyOf(errors);
    }

    public static RequestValidationException of(String field, String message) {
        return new RequestValidationException(List.of(new FieldErrorDetail(field, message)));
    }

    public List<FieldErrorDetail> getErrors() {
        return errors;
    }
}
