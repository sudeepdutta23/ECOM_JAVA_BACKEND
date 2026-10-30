package com.ecommerce.backend.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.annotations.UuidGenerator;

/**
 * Shared identity for all entities: a UUID v7 primary key and correct equals/hashCode.
 *
 * <p><b>Why not Lombok {@code @Data} or {@code @EqualsAndHashCode}?</b> Generated equals would
 * compare every field, touch lazy associations (triggering queries), and change hash codes when
 * fields change, which breaks {@code HashSet}s. JPA entities need identity-based equality:
 * <ul>
 *   <li>Two instances are equal only if they have the same non-null ID and the same real class
 *       ({@link Hibernate#getClass} unwraps lazy proxies).</li>
 *   <li>{@code hashCode} is constant per class, so it stays stable when the ID is assigned on
 *       persist.</li>
 * </ul>
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(algorithm = UuidV7Generator.class)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    public UUID getId() {
        return id;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        UUID otherId = ((BaseEntity) other).getId();
        return id != null && id.equals(otherId);
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
