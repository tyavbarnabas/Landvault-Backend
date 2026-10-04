package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.dto.EstateStateOverrideDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SB-1's escape hatch: a Super Admin verifies an estate's declared state by
 * hand, and the boundary-in-state check is skipped for it. For genuinely
 * disputed borders, where the reference data and the land registry disagree.
 * The actor is always the caller, never the body; the reason is required.
 * Reads under the platform-scope RLS bypass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminEstateStateService {

    private final EstateRepository estateRepository;
    private final AuditApi auditApi;

    @Transactional
    public EstateStateOverrideDto setOverride(UUID estateId, String reason) {
        TenantScope scope = currentScope();
        Estate estate = estateRepository.findById(estateId).orElseThrow(InventoryException.EstateNotFound::new);
        estate.setStateOverrideAt(Instant.now());
        estate.setStateOverrideBy(scope.userId());
        estate.setStateOverrideReason(reason.trim());
        estateRepository.saveAndFlush(estate);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.state_override_set", "estate", estateId, estate.getTenantId(),
                "State of estate '" + estate.getName() + "' (" + estate.getState() + ") verified by hand; the "
                        + "boundary-in-state check is skipped. Reason: " + reason.trim()));
        log.info("State override set on estate {} by {}", estateId, scope.userId());
        return toDto(estate);
    }

    @Transactional
    public EstateStateOverrideDto clearOverride(UUID estateId) {
        TenantScope scope = currentScope();
        Estate estate = estateRepository.findById(estateId).orElseThrow(InventoryException.EstateNotFound::new);
        if (estate.getStateOverrideAt() == null) {
            return toDto(estate);
        }
        estate.setStateOverrideAt(null);
        estate.setStateOverrideBy(null);
        estate.setStateOverrideReason(null);
        estateRepository.saveAndFlush(estate);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.state_override_cleared", "estate", estateId, estate.getTenantId(),
                "State verification on estate '" + estate.getName() + "' removed; the boundary-in-state check applies again."));
        return toDto(estate);
    }

    private static EstateStateOverrideDto toDto(Estate estate) {
        return new EstateStateOverrideDto(estate.getId(), estate.getState(), estate.getStateCode(),
                estate.getStateOverrideAt(), estate.getStateOverrideBy(), estate.getStateOverrideReason());
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
