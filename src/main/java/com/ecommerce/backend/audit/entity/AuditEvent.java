package com.ecommerce.backend.audit.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One audit entry. {@code @Immutable}: Hibernate never issues an UPDATE, and the database refuses one anyway. */
@Entity
@Immutable
@Table(name = "audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent extends BaseEntity {

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, updatable = false, length = 60)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "detail", updatable = false, length = 500)
    private String detail;

    @Column(name = "request_id", updatable = false, length = 100)
    private String requestId;

    @Column(name = "client_ip", updatable = false, length = 45)
    private String clientIp;

    public static AuditEvent record(Instant occurredAt, UUID actorId, String action, String targetType, UUID targetId,
                                    UUID organizationId, String detail, String requestId, String clientIp) {
        AuditEvent event = new AuditEvent();
        event.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        event.actorId = actorId;
        event.action = Objects.requireNonNull(action, "action");
        event.targetType = Objects.requireNonNull(targetType, "targetType");
        event.targetId = targetId;
        event.organizationId = organizationId;
        event.detail = detail;
        event.requestId = requestId;
        event.clientIp = clientIp;
        return event;
    }

    @Override
    public String toString() {
        return "AuditEvent[id=" + getId() + ", action=" + action + ", targetId=" + targetId + "]";
    }
}
