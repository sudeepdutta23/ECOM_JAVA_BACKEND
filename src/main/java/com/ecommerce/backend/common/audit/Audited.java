package com.ecommerce.backend.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose successful completion is recorded in the audit log (Phase 9).
 *
 * <p>The entry is written in the method's own transaction: if the action rolls back, so does its
 * audit entry, and an action that committed always has one. Failures are not audited here; they are
 * in the application log.
 *
 * <p>Expressions are SpEL over the method's parameters ({@code #userId}) and, after it returned,
 * its result ({@code #result.id}). Never put personal data into {@link #detail}: the audit log keeps
 * IDs, and is kept longer than most data.
 *
 * <pre>{@code
 * @Audited(action = "user.suspend", targetType = "user", targetId = "#userId")
 * public AdminUserDetail suspend(UUID callerId, UUID userId, String reason) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** What happened, as {@code area.verb}: {@code user.suspend}, {@code org.approve}. */
    String action();

    /** The kind of thing acted on: {@code user}, {@code organization}, {@code role}. */
    String targetType();

    /** SpEL yielding the target's ID. */
    String targetId();

    /** SpEL yielding the organisation the action belongs to, if any; empty for none. */
    String organizationId() default "";

    /** SpEL yielding a short, non-personal detail, for example {@code "'role=' + #newRole"}; empty for none. */
    String detail() default "";
}
