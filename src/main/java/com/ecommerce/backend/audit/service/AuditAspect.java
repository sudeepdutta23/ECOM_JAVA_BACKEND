package com.ecommerce.backend.audit.service;

import com.ecommerce.backend.audit.entity.AuditEvent;
import com.ecommerce.backend.audit.repository.AuditEventRepository;
import com.ecommerce.backend.common.audit.Audited;
import com.ecommerce.backend.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes an audit entry when an {@link Audited} method returns normally.
 *
 * <p><b>Inside the action's transaction.</b> Transactions are the outermost advice
 * ({@code TransactionConfig}, order 0) and this aspect runs inside them, so the entry commits with
 * the action or rolls back with it. For a method without its own transaction the repository's
 * {@code save} opens one. (An aspect is not proxied itself, so {@code @Transactional} here would do nothing.)
 *
 * <p>The actor is the signed-in account (the access token's subject), never a value from the
 * request body; the request ID ties the entry to the application log.
 */
@Aspect
@Component
@Order(100)
class AuditAspect {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    private final AuditEventRepository events;
    private final Clock clock;

    AuditAspect(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** {@code save} joins the action's transaction (or opens one if the method has none). */
    @AfterReturning(pointcut = "@annotation(audited)", returning = "result")
    public void record(JoinPoint joinPoint, Audited audited, Object result) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(null, method, joinPoint.getArgs(), NAMES);
        context.setVariable("result", result);
        events.save(AuditEvent.record(Instant.now(clock), actor(), audited.action(), audited.targetType(),
                uuid(audited.targetId(), context), uuid(audited.organizationId(), context),
                text(audited.detail(), context), RequestIdFilter.currentRequestId(), clientIp()));
    }

    private static UUID uuid(String expression, MethodBasedEvaluationContext context) {
        if (expression.isEmpty()) {
            return null;
        }
        Object value = parse(expression).getValue(context);
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static String text(String expression, MethodBasedEvaluationContext context) {
        if (expression.isEmpty()) {
            return null;
        }
        Object value = parse(expression).getValue(context);
        return value == null ? null : value.toString();
    }

    private static Expression parse(String expression) {
        return PARSER.parseExpression(expression);
    }

    private static UUID actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        return null; // the system: bootstrap, jobs
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr(); // behind a proxy: forward-headers-strategy (prod) makes this the client
        }
        return null;
    }
}
