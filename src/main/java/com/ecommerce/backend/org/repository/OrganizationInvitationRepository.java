package com.ecommerce.backend.org.repository;

import com.ecommerce.backend.org.entity.OrganizationInvitation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {

    /** Locks the row: a link clicked twice at once is accepted exactly once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrganizationInvitation i where i.tokenHash = :tokenHash")
    Optional<OrganizationInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Scoped by organisation: an invitation ID of another seller is "not found". */
    Optional<OrganizationInvitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The one open invitation for this address, expired or not. Served by {@code uk_organization_invitations_open}. */
    @Query("""
            select i from OrganizationInvitation i
            where i.organizationId = :organizationId and i.email = :email
              and i.acceptedAt is null and i.revokedAt is null
            """)
    Optional<OrganizationInvitation> findOpen(@Param("organizationId") UUID organizationId, @Param("email") String email);

    /** Open and unexpired invitations, newest first. Served by {@code uk_organization_invitations_open}. */
    @Query(value = """
            select i from OrganizationInvitation i
            where i.organizationId = :organizationId and i.acceptedAt is null and i.revokedAt is null
              and i.expiresAt > :now
            order by i.createdAt desc, i.id desc
            """,
            countQuery = """
            select count(i) from OrganizationInvitation i
            where i.organizationId = :organizationId and i.acceptedAt is null and i.revokedAt is null
              and i.expiresAt > :now
            """)
    Page<OrganizationInvitation> findPending(@Param("organizationId") UUID organizationId, @Param("now") Instant now,
                                             Pageable page);

    @Query("""
            select count(i) from OrganizationInvitation i
            where i.organizationId = :organizationId and i.acceptedAt is null and i.revokedAt is null
              and i.expiresAt > :now
            """)
    long countPending(@Param("organizationId") UUID organizationId, @Param("now") Instant now);

    /** Closing an organisation: its open links stop working. Served by {@code uk_organization_invitations_open}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrganizationInvitation i set i.revokedAt = :now
            where i.organizationId = :organizationId and i.acceptedAt is null and i.revokedAt is null
            """)
    int revokeAllOpen(@Param("organizationId") UUID organizationId, @Param("now") Instant now);

    /**
     * Erasure: every invitation ever sent to the address, in any state and organisation, because
     * each row holds the email. Served by {@code idx_organization_invitations_email} (V9).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OrganizationInvitation i where i.email = :email")
    int deleteAllByEmail(@Param("email") String email);
}
