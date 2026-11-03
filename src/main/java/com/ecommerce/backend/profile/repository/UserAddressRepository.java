package com.ecommerce.backend.profile.repository;

import com.ecommerce.backend.profile.entity.UserAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every lookup is scoped by the owner: there is deliberately no way to load an address by its ID
 * alone, so another person's address ID finds nothing (404), exactly like an unknown one.
 */
public interface UserAddressRepository extends JpaRepository<UserAddress, UUID> {

    /** Newest first: UUID v7 IDs are time-ordered. Served by {@code idx_user_addresses_user_id}. */
    List<UserAddress> findAllByUserIdOrderByIdDesc(UUID userId);

    Optional<UserAddress> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    /**
     * Unsets the current default shipping address, except {@code keepId}, before another one becomes
     * default: the partial unique index would reject two at once. No clear: the address being
     * edited is loaded in this context and must stay managed.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update UserAddress a set a.defaultShipping = false, a.version = a.version + 1
            where a.userId = :userId and a.defaultShipping = true and a.id <> :keepId
            """)
    int clearDefaultShipping(@Param("userId") UUID userId, @Param("keepId") UUID keepId);

    /** Like {@link #clearDefaultShipping}, for billing. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update UserAddress a set a.defaultBilling = false, a.version = a.version + 1
            where a.userId = :userId and a.defaultBilling = true and a.id <> :keepId
            """)
    int clearDefaultBilling(@Param("userId") UUID userId, @Param("keepId") UUID keepId);

    /**
     * Serializes address-book writes of one account until commit, so the limit and the default
     * switch cannot be raced by parallel requests. An advisory lock, not a row lock: the account
     * row is only share-locked (against erasure), and there may be no address row to lock yet.
     *
     * @param namespace separates this use from other advisory locks
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    int lockAddressBook(@Param("namespace") int namespace, @Param("key") int key);

    /** Erasure. Flushes the erasure's pending changes first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserAddress a where a.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
