package com.techcomfort.landvaultbackend.portfolio.internal.repository;

import com.techcomfort.landvaultbackend.portfolio.internal.domain.OwnedPlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnedPlotRepository extends JpaRepository<OwnedPlot, UUID> {

    List<OwnedPlot> findByBuyerUserIdOrderByAcquiredAtDesc(UUID buyerUserId);

    Optional<OwnedPlot> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    Optional<OwnedPlot> findByTransactionIdAndBuyerUserId(UUID transactionId, UUID buyerUserId);
}
