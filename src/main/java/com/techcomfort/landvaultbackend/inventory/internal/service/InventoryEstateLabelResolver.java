package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.conflicts.EstateLabelResolver;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import com.techcomfort.landvaultbackend.inventory.internal.repository.BlockRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code inventory}'s side of the inverted resolver {@code conflicts}
 * declares — see {@link EstateLabelResolver} for why the interface lives
 * over there rather than an {@code InventoryApi} living here.
 * <p>
 * Ordinary repository reads, so RLS applies exactly as it does everywhere
 * else in this module: a platform-scope caller (the admin queue) resolves
 * every estate, a tenant-scoped caller (their own conflict view) resolves
 * only their own. That is the desired behaviour in both cases and needs no
 * branch here.
 */
@Service
@RequiredArgsConstructor
public class InventoryEstateLabelResolver implements EstateLabelResolver {

    private final EstateRepository estateRepository;
    private final PlotRepository plotRepository;
    private final BlockRepository blockRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> estateNamesFor(Collection<UUID> estateIds) {
        if (estateIds == null || estateIds.isEmpty()) {
            return Map.of();
        }
        return estateRepository.findAllById(estateIds).stream()
                .collect(Collectors.toMap(Estate::getId, Estate::getName));
    }

    /**
     * "Block A / Plot 12" where the plot has a block, otherwise just the
     * plot number — a bare "12" in a cross-estate conflict queue tells a
     * reviewer nothing.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> plotLabelsFor(Collection<UUID> plotIds) {
        if (plotIds == null || plotIds.isEmpty()) {
            return Map.of();
        }
        List<Plot> plots = plotRepository.findAllById(plotIds);

        // One query for every block involved, not one per plot.
        Map<UUID, Block> blocks = blockRepository.findAllById(
                        plots.stream().map(Plot::getBlockId).filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Block::getId, Function.identity()));

        Map<UUID, String> labels = new HashMap<>();
        for (Plot plot : plots) {
            Block block = plot.getBlockId() == null ? null : blocks.get(plot.getBlockId());
            String blockLabel = block == null
                    ? null
                    : (block.getLabel() == null || block.getLabel().isBlank() ? block.getName() : block.getLabel());
            labels.put(plot.getId(),
                    blockLabel == null
                            ? "Plot " + plot.getPlotNumber()
                            : blockLabel + " / Plot " + plot.getPlotNumber());
        }
        return labels;
    }
}
