package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.PlotView;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlotViewRepository extends Repository<PlotView, UUID> {

    /** Only plots with a boundary: the rest have nothing to draw. */
    List<PlotView> findByEstateIdAndFootprintIsNotNull(UUID estateId);

    /**
     * One plot, if its estate is currently eligible — the view joins
     * eligibility, so an ineligible estate's plots are simply not here.
     */
    Optional<PlotView> findByPlotId(UUID plotId);
}
