package com.ecommerce.backend.audit.repository;

import com.ecommerce.backend.audit.entity.AuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Inserts and reads only: there is no update or delete, and the database would refuse one. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    /**
     * An investigation's filters, each optional, plus the keyset cursor: entries older than
     * {@code before} (UUID v7, so ID order is time order). Matches the actor, target and organisation indexes.
     */
    static Specification<AuditEvent> matching(UUID actorId, UUID targetId, UUID organizationId, String action,
                                              UUID before) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (actorId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("actorId"), actorId));
            }
            if (targetId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("targetId"), targetId));
            }
            if (organizationId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("organizationId"), organizationId));
            }
            if (action != null) {
                predicate = cb.and(predicate, cb.equal(root.get("action"), action));
            }
            if (before != null) {
                predicate = cb.and(predicate, cb.lessThan(root.get("id"), before));
            }
            return predicate;
        };
    }
}
