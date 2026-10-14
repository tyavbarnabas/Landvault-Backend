package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code roles} is not RLS-policied (login reads it before any scope exists),
 * so the tenant condition in the "assignable" queries is the only thing that
 * keeps one company's roles from another. See AGENTS.md, "Company-made roles".
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    /** HomeTrue's assignable roles plus this company's own. */
    @Query("SELECT r FROM Role r WHERE r.scope IS NOT NULL AND (r.tenantId IS NULL OR r.tenantId = :tenantId)")
    List<Role> findAssignable(@Param("tenantId") UUID tenantId);

    @Query("SELECT r FROM Role r WHERE r.code = :code AND r.scope IS NOT NULL "
            + "AND (r.tenantId IS NULL OR r.tenantId = :tenantId)")
    Optional<Role> findAssignableByCode(@Param("code") String code, @Param("tenantId") UUID tenantId);

    Optional<Role> findByCodeAndTenantId(String code, UUID tenantId);

    long countByTenantId(UUID tenantId);

    boolean existsByTenantIdIsNullAndNameIgnoreCase(String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);
}
