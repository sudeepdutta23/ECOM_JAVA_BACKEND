package com.ecommerce.backend.audit.service;

import com.ecommerce.backend.audit.dto.AuditEventResponse;
import com.ecommerce.backend.audit.dto.AuditPage;
import com.ecommerce.backend.audit.entity.AuditEvent;
import com.ecommerce.backend.audit.repository.AuditEventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading the audit log, for staff with {@code audit:read}. */
@Service
public class AuditQueryService {

    private final AuditEventRepository events;

    public AuditQueryService(AuditEventRepository events) {
        this.events = events;
    }

    /**
     * Keyset paging: one row more than asked tells whether there is a next page, and the last
     * returned ID is its cursor. No count query, and the cost stays the same however deep one reads.
     */
    @Transactional(readOnly = true)
    public AuditPage search(UUID actorId, UUID targetId, UUID organizationId, String action, UUID before, int size) {
        List<AuditEvent> found = events.findAll(AuditEventRepository.matching(actorId, targetId, organizationId, action, before),
                PageRequest.of(0, size + 1, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        boolean more = found.size() > size;
        List<AuditEvent> page = more ? found.subList(0, size) : found;
        return new AuditPage(page.stream().map(AuditEventResponse::from).toList(),
                more ? page.get(page.size() - 1).getId() : null);
    }
}
