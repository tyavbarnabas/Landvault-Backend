package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.common.EstateIntent;
import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.FeedView;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The feed's filters: the estate filters ({@link ListingViewSpecifications})
 * applied to estates and resale plots alike, plus which kind and which estate.
 * A resale plot's size is its own, so its size range is that one size.
 */
public final class FeedViewSpecifications {

    private FeedViewSpecifications() {
    }

    /** {@code listingType} PRIMARY, RESALE, or null for both. */
    public static Specification<FeedView> matching(ListingViewSpecifications.Filters f, String listingType,
                                                   UUID estateId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (listingType != null) {
                predicates.add(cb.equal(root.get("listingType"), listingType));
            }
            if (estateId != null) {
                predicates.add(cb.equal(root.get("estateId"), estateId));
            }
            if (f.query() != null && !f.query().isBlank()) {
                String like = "%" + f.query().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("area")), like),
                        cb.like(cb.lower(root.get("city")), like),
                        cb.like(cb.lower(root.get("state")), like)));
            }
            if (f.state() != null && !f.state().isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("state")), f.state().trim().toLowerCase(Locale.ROOT)));
            }
            if (f.city() != null && !f.city().isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("city")), f.city().trim().toLowerCase(Locale.ROOT)));
            }
            // A price only means something in its own currency (see ListingViewSpecifications).
            if (f.minPrice() != null || f.maxPrice() != null) {
                predicates.add(cb.equal(root.get("currency"), f.priceCurrency()));
            }
            if (f.minPrice() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), f.minPrice()));
            }
            if (f.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), f.maxPrice()));
            }
            if (f.minSize() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("maxSizeSqm"), f.minSize()));
            }
            if (f.maxSize() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("minSizeSqm"), f.maxSize()));
            }
            // A resale plot's title is its estate's, so the same verified-only rule.
            if (f.titleType() != null) {
                predicates.add(cb.equal(root.get("titleType"), f.titleType()));
                predicates.add(cb.equal(root.get("titleStatus"), TitleStatus.VERIFIED));
            }
            if (f.intent() != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("intent"), f.intent()),
                        cb.equal(root.get("intent"), EstateIntent.BOTH)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
