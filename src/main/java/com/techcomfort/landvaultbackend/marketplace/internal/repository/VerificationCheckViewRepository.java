package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.VerificationCheckView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VerificationCheckViewRepository extends Repository<VerificationCheckView, UUID> {

    List<VerificationCheckView> findByEstateIdIn(Collection<UUID> estateIds);
}
