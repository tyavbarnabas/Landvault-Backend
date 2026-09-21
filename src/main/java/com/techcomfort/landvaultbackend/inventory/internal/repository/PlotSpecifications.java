package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import com.techcomfort.landvaultbackend.inventory.internal.enums.ListingIntent;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PlotStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.PropertyType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Filters for one estate's plot list. As with {@link EstateSpecifications},
 * tenant and branch scoping is RLS's job — the only scoping predicate here
 * is {@code estateId}, and the estate itself was already fetched under the
 * caller's scope before this runs.
 */
public final class PlotSpecifications {

    private PlotSpecifications() {
    }

    public static Specification<Plot> matching(
            UUID estateId, String status, UUID blockId, UUID priceTierId,
            Boolean isCorner, String propertyType, String listingIntent) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("estateId"), estateId));
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), PlotStatus.fromValue(status)));
            }
            if (blockId != null) {
                predicates.add(cb.equal(root.get("blockId"), blockId));
            }
            if (priceTierId != null) {
                predicates.add(cb.equal(root.get("priceTierId"), priceTierId));
            }
            if (isCorner != null) {
                predicates.add(cb.equal(root.get("isCorner"), isCorner));
            }
            if (propertyType != null && !propertyType.isBlank()) {
                predicates.add(cb.equal(root.get("propertyType"), PropertyType.fromValue(propertyType)));
            }
            if (listingIntent != null && !listingIntent.isBlank()) {
                predicates.add(cb.equal(root.get("listingIntent"), ListingIntent.fromValue(listingIntent)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
