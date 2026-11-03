package com.ecommerce.backend.profile.repository;

import com.ecommerce.backend.profile.entity.UserProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Profiles by account ID; every lookup is a primary-key lookup. */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    /** Erasure. Flushes first, so the erasure's own pending changes are written before the context is cleared. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserProfile p where p.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
