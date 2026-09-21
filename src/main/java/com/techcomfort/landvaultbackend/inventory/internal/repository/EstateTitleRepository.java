package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EstateTitleRepository extends JpaRepository<EstateTitle, UUID> {

    // 1:1 with the estate — a second title row would make "the" title ambiguous.
    boolean existsByEstateId(UUID estateId);

    Optional<EstateTitle> findByEstateId(UUID estateId);
}
