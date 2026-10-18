package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;

/**
 * A version's review status, derived — never stored, so it can't disagree with
 * the decisions: HomeTrue's decision if there is one; otherwise {@code pending}
 * for the estate's newest version and {@code superseded} for an older one,
 * which no letter will ever use.
 */
final class ReviewStatus {

    static final String PENDING = "pending";
    static final String SUPERSEDED = "superseded";

    private ReviewStatus() {
    }

    static String of(int version, ReviewDecision decision, int newestVersion) {
        if (decision != null) {
            return decision.value();
        }
        return version == newestVersion ? PENDING : SUPERSEDED;
    }
}
