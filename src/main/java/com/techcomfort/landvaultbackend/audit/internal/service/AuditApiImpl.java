package com.techcomfort.landvaultbackend.audit.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.audit.internal.domain.AuditLogEntry;
import com.techcomfort.landvaultbackend.audit.internal.repository.AuditLogEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuditApiImpl implements AuditApi {

    private final AuditLogEntryRepository repository;

    @Override
    @Transactional
    public void record(AuditEntryRequest request) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .actorUserId(request.actorUserId())
                .action(request.action())
                .targetType(request.targetType())
                .targetId(request.targetId())
                .tenantId(request.tenantId())
                .detail(request.detail())
                .privileged(request.privileged())
                .occurredAt(Instant.now())
                .build();
        repository.save(entry);
    }
}
