package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlotRepository extends JpaRepository<Plot, UUID> {
}
