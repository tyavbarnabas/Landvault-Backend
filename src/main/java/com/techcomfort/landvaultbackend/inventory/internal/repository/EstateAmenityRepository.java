package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateAmenity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EstateAmenityRepository extends JpaRepository<EstateAmenity, UUID> {

    List<EstateAmenity> findByEstateIdOrderByNameAsc(UUID estateId);
}
