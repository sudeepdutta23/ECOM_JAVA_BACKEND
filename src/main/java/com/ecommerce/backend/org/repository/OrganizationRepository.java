package com.ecommerce.backend.org.repository;

import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.entity.Organization;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /** Served by {@code uk_organizations_slug}. Closed organisations keep their slug for good. */
    boolean existsBySlug(String slug);

    /** Storefront: a seller by its public handle. Served by {@code uk_organizations_slug}. */
    Optional<Organization> findBySlug(String slug);

    /** The status with a FOR SHARE lock until commit, for checkout ({@code SellerDirectory.lockIfSelling}). */
    @Query(value = "SELECT status FROM organizations WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<String> lockStatus(@Param("id") UUID id);

    /** The platform's list, filtered by status. Served by {@code idx_organizations_status_id}. */
    Page<Organization> findByStatus(OrganizationStatus status, Pageable page);

    /**
     * The open organisations this user owns, locked in ID order. Before an account goes away, each
     * one is checked for another owner; the locks stop a co-owner from leaving in the meantime, and
     * the fixed order stops two departures from deadlocking on each other's organisations.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from Organization o
            where o.status <> com.ecommerce.backend.org.OrganizationStatus.CLOSED
              and o.id in (select m.organizationId from OrganizationMember m
                           where m.userId = :userId and m.role = com.ecommerce.backend.org.OrgRole.OWNER)
            order by o.id
            """)
    List<Organization> lockOpenOwnedBy(@Param("userId") UUID userId);

    /**
     * Locks the organisation row until the transaction ends. Every change to who holds {@code OWNER}
     * takes it first, so two owners demoting or removing each other at once are serialised and the
     * second one sees that only one owner is left (the last-owner rule, like Phase 2's ADMIN lock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Serialises organisation creation per founder until the transaction ends, so two parallel
     * requests cannot both pass the owned-organisation limit.
     *
     * <p><b>Why an advisory lock?</b> There is no row to lock yet: the organisation does not exist,
     * and the founder's {@code users} row belongs to the auth module. A transaction-scoped advisory
     * lock on a number derived from the user ID needs no table and is released at commit or
     * rollback. Two users whose IDs hash alike merely wait for each other briefly.
     *
     * @param namespace separates this use from other advisory locks
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    int lockCreationFor(@Param("namespace") int namespace, @Param("key") int key);
}
