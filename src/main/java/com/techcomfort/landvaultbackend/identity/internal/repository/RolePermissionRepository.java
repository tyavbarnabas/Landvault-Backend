package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
