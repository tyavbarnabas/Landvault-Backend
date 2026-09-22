package com.techcomfort.landvaultbackend.marketplace.internal.repository;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.EstateIntent;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.ListingView;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Buyer filters (MP-4). None of these is an eligibility check and none needs
 * to be: an ineligible estate is not in the view at all.
 */
public final class ListingViewSpecifications {

    private ListingViewSpecifications() {
    }

    public record Filters(
            String query, String state, String city,
            BigDecimal minPrice, BigDecimal maxPrice, Currency priceCurrency,
            BigDecimal minSize, BigDecimal maxSize,
            TitleType titleType, EstateIntent intent
    ) {
    }

    public static Specification<ListingView> matching(Filters f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
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
            // A price only means something in its own currency. Comparing a
            // ₦20,000,000 "from" price against a $50,000 one would rank land
            // by a number that describes nothing, so a price filter applies
            // within one currency (NGN unless the caller says otherwise).
            if (f.minPrice() != null || f.maxPrice() != null) {
                predicates.add(cb.equal(root.get("fromPriceCurrency"), f.priceCurrency()));
            }
            if (f.minPrice() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("fromPrice"), f.minPrice()));
            }
            if (f.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("fromPrice"), f.maxPrice()));
            }
            // Size ranges overlap: the estate sells some size inside the band.
            if (f.minSize() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("maxSizeSqm"), f.minSize()));
            }
            if (f.maxSize() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("minSizeSqm"), f.maxSize()));
            }
            if (f.titleType() != null) {
                predicates.add(cb.equal(root.get("titleType"), f.titleType()));
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
