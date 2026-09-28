package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotCountsDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierImpactDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierUpdateDto;
import com.techcomfort.landvaultbackend.inventory.dto.UpdateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.UpdatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TierType;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.BlockRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PriceTierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Editing inventory that already exists — slice 1: tier price, label and
 * size, and block names. Creation lives in {@code PortalEstateService}.
 * <p>
 * Every edit is audited with the previous value in the free-text
 * {@code detail}: a price change is exactly what a tenant later disputes. An
 * edit that changes nothing writes nothing, the same no-op rule publish uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryEditService {

    private static final List<PlotStatus> AVAILABLE =
            List.of(PlotStatus.AVAILABLE_DEV, PlotStatus.AVAILABLE_INV);

    private final EstateRepository estateRepository;
    private final PriceTierRepository priceTierRepository;
    private final PlotRepository plotRepository;
    private final BlockRepository blockRepository;
    private final AuditApi auditApi;

    // --- price tier ---

    @Transactional
    public PriceTierUpdateDto updatePriceTier(UUID estateId, UUID tierId, UpdatePriceTierRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        PriceTier tier = requireTier(estateId, tierId);

        // A different type or currency is a different tier. Echoing the
        // current value back is fine; anything else is refused, not ignored.
        if (request.tierType() != null && TierType.fromValue(request.tierType()) != tier.getTierType()) {
            throw new InventoryException.ImmutableField("TIER_TYPE_IMMUTABLE",
                    "A tier's type cannot change. Create a new tier and move the plots to it.");
        }
        if (request.currency() != null && request.currency() != tier.getCurrency()) {
            throw new InventoryException.ImmutableField("TIER_CURRENCY_IMMUTABLE",
                    "A tier's currency cannot change. Create a new tier in that currency and move the plots to it.");
        }
        if (request.sizeSqm() != null && tier.getTierType() != TierType.LAND_SIZE) {
            throw new InventoryException.InvalidRequest(
                    "A UNIT_TYPE tier has no size of its own — its label carries the meaning.");
        }

        List<String> changes = new ArrayList<>();

        if (request.price() != null && request.price().compareTo(tier.getPrice()) != 0) {
            changes.add("price " + tier.getCurrency() + " " + tier.getPrice().toPlainString()
                    + " -> " + tier.getCurrency() + " " + request.price().toPlainString());
            tier.setPrice(request.price());
        }

        if (request.label() != null) {
            String label = request.label().isBlank() ? null : request.label().trim();
            if (!Objects.equals(label, tier.getLabel())) {
                changes.add("label '" + nullToEmpty(tier.getLabel()) + "' -> '" + nullToEmpty(label) + "'");
                tier.setLabel(label);
            }
        }

        BigDecimal previousSize = tier.getSizeSqm();
        boolean sizeChanged = request.sizeSqm() != null && request.sizeSqm().compareTo(previousSize) != 0;
        if (sizeChanged) {
            if (priceTierRepository.existsByEstateIdAndSizeSqmAndIdNot(estateId, request.sizeSqm(), tierId)) {
                throw new InventoryException.DuplicateRecord(
                        "A tier for " + request.sizeSqm().toPlainString() + " sqm already exists on this estate.");
            }
            tier.setSizeSqm(request.sizeSqm());
        }

        if (changes.isEmpty() && !sizeChanged) {
            return new PriceTierUpdateDto(toDto(tier), null);
        }

        // Through the repository, not EntityManager.flush(): a constraint
        // violation here must arrive as DataIntegrityViolationException so
        // the handler maps it to 409 (see AGENTS.md). Also takes the tier
        // row's lock before the plots are touched — the plot-release
        // function waits on that lock, so a hold ending mid-edit reads the
        // new size rather than the old.
        tier = priceTierRepository.saveAndFlush(tier);

        PriceTierUpdateDto.SizeChange sizeChange = null;
        if (sizeChanged) {
            int updated = plotRepository.resizeAvailablePlotsOnTier(
                    tierId, tier.getSizeSqm(), scope.userId().toString());
            PlotCountsDto kept = countsForTier(tierId, true);
            changes.add("size " + previousSize.toPlainString() + " -> " + tier.getSizeSqm().toPlainString()
                    + " sqm, applied to " + updated + " available plot(s); " + kept.total()
                    + " reserved or sold plot(s) kept " + previousSize.toPlainString() + " sqm");
            sizeChange = new PriceTierUpdateDto.SizeChange(
                    previousSize, tier.getSizeSqm(), updated, kept,
                    kept.total() == 0
                            ? "Every plot on this tier now carries the new size."
                            : "Reserved and sold plots keep the size their buyer agreed to. A reserved plot "
                                    + "that returns to the market takes the tier's size at that point.");
        }

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.price_tier_updated", "price_tier", tierId, estate.getTenantId(),
                "Price tier on estate " + estateId + " changed: " + String.join("; ", changes) + "."));
        log.info("Price tier {} on estate {} updated by {}", tierId, estateId, scope.userId());

        return new PriceTierUpdateDto(toDto(tier), sizeChange);
    }

    /** IE-2. A read, so any holder of {@code portal.estates.view} may preview. */
    @Transactional(readOnly = true)
    public PriceTierImpactDto priceTierImpact(UUID estateId, UUID tierId) {
        TenantScope scope = currentScope();
        requireOwnedEstate(estateId, requireTenant(scope));
        requireTier(estateId, tierId);
        return new PriceTierImpactDto(tierId, countsForTier(tierId, false));
    }

    // --- block ---

    @Transactional
    public BlockDto updateBlock(UUID estateId, UUID blockId, UpdateBlockRequest request) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId, requireTenant(scope));
        Block block = blockRepository.findByIdAndEstateId(blockId, estateId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Block " + blockId + " does not belong to this estate."));

        List<String> changes = new ArrayList<>();

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new InventoryException.InvalidRequest("A block name cannot be blank.");
            }
            String name = request.name().trim();
            if (!name.equals(block.getName())) {
                if (blockRepository.existsByEstateIdAndNameIgnoreCaseAndIdNot(estateId, name, blockId)) {
                    throw new InventoryException.DuplicateRecord(
                            "Block '" + name + "' already exists on this estate.");
                }
                changes.add("name '" + block.getName() + "' -> '" + name + "'");
                block.setName(name);
            }
        }

        if (request.label() != null) {
            String label = request.label().isBlank() ? null : request.label().trim();
            if (!Objects.equals(label, block.getLabel())) {
                changes.add("label '" + nullToEmpty(block.getLabel()) + "' -> '" + nullToEmpty(label) + "'");
                block.setLabel(label);
            }
        }

        if (changes.isEmpty()) {
            return toDto(block);
        }

        // saveAndFlush so a lost race on uq_blocks_estate_name surfaces here
        // as a translated DataIntegrityViolationException — a 409 — not as a
        // raw error at commit.
        block = blockRepository.saveAndFlush(block);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.block_updated", "block", blockId, estate.getTenantId(),
                "Block on estate " + estateId + " changed: " + String.join("; ", changes) + "."));
        return toDto(block);
    }

    // --- shared ---

    /**
     * Counts for one tier, by status wire value — only statuses that occur,
     * as {@link PlotCountsDto} requires. {@code excludeAvailable} gives the
     * plots a size change deliberately left alone.
     */
    private PlotCountsDto countsForTier(UUID tierId, boolean excludeAvailable) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : plotRepository.countGroupedByStatusForTier(tierId)) {
            PlotStatus status = (PlotStatus) row[0];
            if (excludeAvailable && AVAILABLE.contains(status)) {
                continue;
            }
            long count = ((Number) row[1]).longValue();
            byStatus.put(status.getValue(), count);
            total += count;
        }
        return new PlotCountsDto(total, byStatus);
    }

    private PriceTier requireTier(UUID estateId, UUID tierId) {
        return priceTierRepository.findByIdAndEstateId(tierId, estateId)
                .orElseThrow(() -> new InventoryException.RelatedRecordNotFound(
                        "Price tier " + tierId + " does not belong to this estate."));
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new InventoryException.InvalidRequest(
                    "This endpoint belongs to a tenant's own portal; the caller has no tenant scope.");
        }
        return scope.tenantId();
    }

    private Estate requireOwnedEstate(UUID estateId, UUID tenantId) {
        return estateRepository.findByIdAndTenantId(estateId, tenantId)
                .orElseThrow(InventoryException.EstateNotFound::new);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static PriceTierDto toDto(PriceTier tier) {
        return new PriceTierDto(tier.getId(), tier.getEstateId(), tier.getTierType().getValue(),
                tier.getSizeSqm(), tier.getPrice(), tier.getCurrency(), tier.getLabel());
    }

    private static BlockDto toDto(Block block) {
        return new BlockDto(block.getId(), block.getEstateId(), block.getName(), block.getLabel());
    }
}
