package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.PlotView;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface PlotViewRepository extends Repository<PlotView, UUID> {

    /** Only plots with a boundary: the rest have nothing to draw. */
    List<PlotView> findByEstateIdAndFootprintIsNotNull(UUID estateId);
}
