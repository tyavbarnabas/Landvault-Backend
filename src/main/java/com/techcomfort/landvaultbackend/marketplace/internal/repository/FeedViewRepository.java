package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.FeedView;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.UUID;

public interface FeedViewRepository extends Repository<FeedView, UUID>, JpaSpecificationExecutor<FeedView> {
}
