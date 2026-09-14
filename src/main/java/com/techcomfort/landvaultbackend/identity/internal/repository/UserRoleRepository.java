package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * {@code UserRole} carries no association to {@code Role}/{@code Permission}
 * (plain UUID FKs, same convention as the rest of the schema), so "eager,
 * no-N+1" for the login path means a small fixed number of batched queries
 * — {@code findByUserId} here, then {@code RoleRepository.findAllById} and
 * {@code RolePermissionRepository.findByRoleIdIn} — rather than one
 * {@code JOIN FETCH}. See {@code AuthService} for the orchestration.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findByUserId(UUID userId);
}
