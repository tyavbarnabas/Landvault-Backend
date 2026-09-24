package com.techcomfort.landvaultbackend.audit.internal.service;

import com.techcomfort.landvaultbackend.audit.ActorNameResolver;
import com.techcomfort.landvaultbackend.audit.dto.AuditLogEntryDto;
import com.techcomfort.landvaultbackend.audit.internal.domain.AuditLogEntry;
import com.techcomfort.landvaultbackend.audit.internal.repository.AuditLogEntryRepository;
import com.techcomfort.landvaultbackend.audit.internal.repository.AuditLogEntrySpecifications;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The audit log's read side. Read-only by construction — this service has no
 * write, update or archive method, and must not gain one.
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private static final String UNKNOWN_ACTOR = "Unknown user";

    /**
     * An entry with no actor at all: the platform acted, not a person. A
     * different fact from an actor whose account was since deleted, so it
     * reads differently.
     */
    private static final String SYSTEM_ACTOR = "System";

    private final AuditLogEntryRepository repository;
    private final ActorNameResolver actorNameResolver;

    @Transactional(readOnly = true)
    public PageResponse<AuditLogEntryDto> search(
            UUID actorUserId, UUID tenantId, String targetType, UUID targetId,
            String action, Boolean privileged, Instant occurredAfter, Instant occurredBefore,
            Pageable pageable) {

        Page<AuditLogEntry> page = repository.findAll(
                AuditLogEntrySpecifications.matching(
                        actorUserId, tenantId, targetType, targetId, action, privileged, occurredAfter, occurredBefore),
                pageable);

        // One resolver call for the whole page, not one per row — see
        // ActorNameResolver.
        Set<UUID> actorIds = page.getContent().stream()
                .map(AuditLogEntry::getActorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = actorNameResolver.displayNamesFor(actorIds);

        return PageResponses.from(page, entry -> toDto(entry, names));
    }

    private static AuditLogEntryDto toDto(AuditLogEntry entry, Map<UUID, String> names) {
        return new AuditLogEntryDto(
                entry.getId(),
                entry.getActorUserId(),
                // Three cases, kept distinct: the platform acted, a person
                // acted, or a person acted and their account is gone. Never
                // blank, never a crash.
                entry.getActorUserId() == null
                        ? SYSTEM_ACTOR
                        : names.getOrDefault(entry.getActorUserId(), UNKNOWN_ACTOR),
                entry.getAction(),
                entry.getTargetType(),
                entry.getTargetId(),
                entry.getTenantId(),
                entry.getDetail(),
                Boolean.TRUE.equals(entry.getPrivileged()),
                entry.getOccurredAt());
    }
}
