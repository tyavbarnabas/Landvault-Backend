package com.techcomfort.landvaultbackend.documents.internal.enums;

/** HomeTrue's decision on one version of developer-written letter wording (conditions, 092; templates, 094). Stored by name. */
public enum ReviewDecision {
    APPROVED("approved"),
    REJECTED("rejected");

    private final String value;

    ReviewDecision(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
