package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    /**
     * Permission codes for a set of roles in one round trip — Permission
     * and RolePermission aren't JPA-associated (plain UUID FKs, matching
     * the rest of the schema's no-cross-entity-association convention), so
     * this is a subquery rather than a real join, but it's still one SQL
     * statement instead of two repository calls. Used on the login path.
     */
    @Query("SELECT p.code FROM Permission p WHERE p.id IN "
            + "(SELECT rp.permissionId FROM RolePermission rp WHERE rp.roleId IN :roleIds)")
    List<String> findCodesByRoleIdIn(@Param("roleIds") Collection<UUID> roleIds);
}
