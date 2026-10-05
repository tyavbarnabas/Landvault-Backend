package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Active company-wide holders of a role in one tenant — who to ask to approve an invitation request. */
    @Query("SELECT DISTINCT u FROM UserRole ur JOIN ur.user u, Role r WHERE r.id = ur.roleId "
            + "AND u.tenantId = :tenantId AND r.code = :roleCode AND ur.scopedBranchId IS NULL AND u.status = :status")
    List<User> findCompanyWideHolders(@Param("tenantId") UUID tenantId, @Param("roleCode") String roleCode,
                                      @Param("status") UserStatus status);
}
