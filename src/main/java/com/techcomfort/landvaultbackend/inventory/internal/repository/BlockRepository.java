package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BlockRepository extends JpaRepository<Block, UUID> {

    boolean existsByEstateIdAndNameIgnoreCase(UUID estateId, String name);

    Optional<Block> findByIdAndEstateId(UUID id, UUID estateId);
}
