package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateAmenity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EstateAmenityRepository extends JpaRepository<EstateAmenity, UUID> {
}
