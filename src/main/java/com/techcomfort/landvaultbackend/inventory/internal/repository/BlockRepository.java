package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockRepository extends JpaRepository<Block, UUID> {

    boolean existsByEstateIdAndNameIgnoreCase(UUID estateId, String name);

    /** A rename's clash check — the block itself is excluded, so a case-only rename is allowed. */
    boolean existsByEstateIdAndNameIgnoreCaseAndIdNot(UUID estateId, String name, UUID id);

    Optional<Block> findByIdAndEstateId(UUID id, UUID estateId);

    List<Block> findByEstateIdOrderByNameAsc(UUID estateId);
}
