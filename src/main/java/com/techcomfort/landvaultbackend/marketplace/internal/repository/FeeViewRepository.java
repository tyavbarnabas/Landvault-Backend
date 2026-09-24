package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.FeeView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FeeViewRepository extends Repository<FeeView, UUID> {

    /** Batched for a whole page — never one query per listing. */
    List<FeeView> findByEstateIdIn(Collection<UUID> estateIds);
}
