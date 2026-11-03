package com.ecommerce.backend.org.repository;

import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.OrganizationMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, OrganizationMember.Key> {

    /**
     * The caller's organisations, newest organisation first, one join per page. The explicit count
     * query avoids counting through the join. Both read {@code idx_organization_members_user_id}.
     */
    @Query(value = """
            select new com.ecommerce.backend.org.repository.MembershipView(
                o.id, o.slug, o.name, o.status, m.role, m.joinedAt)
            from OrganizationMember m join Organization o on o.id = m.organizationId
            where m.userId = :userId
            order by o.id desc
            """,
            countQuery = "select count(m) from OrganizationMember m where m.userId = :userId")
    Page<MembershipView> findMemberships(@Param("userId") UUID userId, Pageable page);

    /**
     * The guard's question in one query: the membership and the organisation's status, both by
     * primary key ({@code pk_organization_members}, {@code pk_organizations}). Empty for non-members.
     */
    @Query("""
            select new com.ecommerce.backend.org.OrgMembership(m.organizationId, m.userId, m.role, o.status)
            from OrganizationMember m join Organization o on o.id = m.organizationId
            where m.organizationId = :organizationId and m.userId = :userId
            """)
    Optional<OrgMembership> findMembership(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId);

    /** Served by {@code pk_organization_members} (leading organization_id). */
    long countByOrganizationIdAndRole(UUID organizationId, OrgRole role);

    /** For the last-owner rule, which counts only owners whose accounts can sign in. Served by {@code pk_organization_members}. */
    @Query("""
            select m.userId from OrganizationMember m
            where m.organizationId = :organizationId and m.role = com.ecommerce.backend.org.OrgRole.OWNER
            """)
    List<UUID> findOwnerIds(@Param("organizationId") UUID organizationId);

    long countByOrganizationId(UUID organizationId);

    /**
     * The organisation's status with a FOR SHARE lock on its row until commit, for
     * {@code OrganizationGuard.requireForSellerWrite}. Here rather than in the organisation repository
     * because the guard reads memberships only; a scalar, so no entity enters the context.
     */
    @Query(value = "SELECT status FROM organizations WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<String> lockOrganizationStatus(@Param("id") UUID organizationId);

    /** All members of an organisation. Served by {@code pk_organization_members}. */
    @Query("select m.userId from OrganizationMember m where m.organizationId = :organizationId order by m.userId")
    List<UUID> findUserIds(@Param("organizationId") UUID organizationId);

    /** Erasure: every membership of the user. Served by {@code idx_organization_members_user_id}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OrganizationMember m where m.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    /** The members of one organisation. Served by {@code pk_organization_members}; sorted per page. */
    Page<OrganizationMember> findByOrganizationId(UUID organizationId, Pageable page);

    /** Organisations this user owns that are not closed: the per-user limit. */
    @Query("""
            select count(m) from OrganizationMember m join Organization o on o.id = m.organizationId
            where m.userId = :userId
              and m.role = com.ecommerce.backend.org.OrgRole.OWNER
              and o.status <> com.ecommerce.backend.org.OrganizationStatus.CLOSED
            """)
    long countOpenOwnedBy(@Param("userId") UUID userId);
}
