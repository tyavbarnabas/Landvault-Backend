package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.PermissionGroup;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Read-only: groups change by migration. */
public interface PermissionGroupRepository extends Repository<PermissionGroup, String> {

    List<PermissionGroup> findAll();
}
