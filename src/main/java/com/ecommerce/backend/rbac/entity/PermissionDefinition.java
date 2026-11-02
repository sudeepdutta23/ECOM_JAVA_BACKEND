package com.ecommerce.backend.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A row of the permission catalogue. Read-only in Java: rows are created by migrations only.
 *
 * <p>{@code @Immutable} makes Hibernate skip dirty checking and refuse updates, which documents
 * and enforces that rule.
 */
@Entity
@Immutable
@Table(name = "permissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PermissionDefinition {

    @Id
    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
