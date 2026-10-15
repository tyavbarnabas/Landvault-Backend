package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.EstatePhotoView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EstatePhotoViewRepository extends Repository<EstatePhotoView, UUID> {

    List<EstatePhotoView> findByEstateIdInOrderByPositionAsc(Collection<UUID> estateIds);
}
