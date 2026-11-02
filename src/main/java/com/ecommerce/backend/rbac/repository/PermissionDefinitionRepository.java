package com.ecommerce.backend.rbac.repository;

import com.ecommerce.backend.rbac.entity.PermissionDefinition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, String> {

    List<PermissionDefinition> findAllByOrderByCodeAsc();
}
