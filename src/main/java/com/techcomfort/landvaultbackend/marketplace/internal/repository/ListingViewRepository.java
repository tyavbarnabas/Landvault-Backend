package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.marketplace.internal.domain.ListingView;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only by construction: extends {@link Repository}, not
 * {@code JpaRepository}, so no save/delete method exists to call. The app
 * role also holds SELECT only on the view (changeset 049).
 */
public interface ListingViewRepository extends Repository<ListingView, UUID>, JpaSpecificationExecutor<ListingView> {

    Optional<ListingView> findById(UUID estateId);
}
