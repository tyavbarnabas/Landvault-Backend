package com.techcomfort.landvaultbackend.audit.internal.repository;

import com.techcomfort.landvaultbackend.audit.internal.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Read and append only. There is deliberately no update or delete path —
 * {@code AuditLogEntry} extends {@code AbstractAppendOnlyEntity} precisely so
 * an entry cannot be edited or hidden, and nothing here should reintroduce
 * that. See AGENTS.md.
 */
public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID>,
        JpaSpecificationExecutor<AuditLogEntry> {
}
