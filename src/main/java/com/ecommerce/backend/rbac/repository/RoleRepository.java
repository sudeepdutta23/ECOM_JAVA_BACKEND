package com.ecommerce.backend.rbac.repository;

import com.ecommerce.backend.rbac.entity.Role;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * All roles WITH their permissions in one query.
     *
     * <p>Without {@code join fetch}, listing N roles and then touching each role's lazy
     * permission collection issues 1 + N queries (the N+1 problem). {@code distinct} removes the
     * duplicate role rows the join produces. No pagination: roles are a short, admin-curated list.
     */
    @Query("select distinct r from Role r left join fetch r.permissions order by r.code")
    List<Role> findAllWithPermissions();

    @Query("select r from Role r left join fetch r.permissions where r.id = :id")
    Optional<Role> findWithPermissionsById(@Param("id") UUID id);

    /**
     * {@code SELECT ... FOR UPDATE} on one role row, held until the transaction ends.
     *
     * <p>Used as a mutex for "who holds ADMIN": every change to that set takes this lock first, so
     * two admins revoking each other run one after the other, and the second sees the first's result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code = :code")
    Optional<Role> lockByCode(@Param("code") String code);
}
