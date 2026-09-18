package com.techcomfort.landvaultbackend.audit.internal.repository;

import com.techcomfort.landvaultbackend.audit.internal.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID> {
}
