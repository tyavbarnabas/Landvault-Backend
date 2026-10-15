package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstatePhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EstatePhotoRepository extends JpaRepository<EstatePhoto, UUID> {

    List<EstatePhoto> findByEstateIdOrderByPositionAsc(UUID estateId);

    Optional<EstatePhoto> findByIdAndEstateId(UUID id, UUID estateId);

    Optional<EstatePhoto> findByFileId(UUID fileId);

    long countByEstateId(UUID estateId);
}
