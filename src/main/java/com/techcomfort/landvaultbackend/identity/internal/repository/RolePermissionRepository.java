package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Not one of the task's explicitly named repositories, but a necessary
 * supporting piece for {@code UserRoleRepository}'s "eager, no-N+1" login
 * load — {@code role_permissions} is its own entity and needs its own
 * repository to batch-query, same as every other table here.
 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    List<RolePermission> findByRoleIdIn(Collection<UUID> roleIds);

    /**
     * Hard delete, for replacing a company-made role's permissions: the unique
     * (role_id, permission_id) constraint counts soft-deleted rows, so a soft
     * delete would make a permission impossible to give back.
     */
    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") UUID roleId);
}
