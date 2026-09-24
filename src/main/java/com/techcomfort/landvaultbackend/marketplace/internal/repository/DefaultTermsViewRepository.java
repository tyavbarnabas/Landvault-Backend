package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.DefaultTermsView;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DefaultTermsViewRepository extends Repository<DefaultTermsView, UUID> {

    List<DefaultTermsView> findByEstateIdIn(Collection<UUID> estateIds);
}
