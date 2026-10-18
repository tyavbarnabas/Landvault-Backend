package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;

import java.util.UUID;

/**
 * Whose letterhead or template a route is about: {@code company}, or the id of
 * one of the caller's company's branches (another company's is a 404).
 */
record LetterTarget(UUID branchId, String branchName) {

    static final String COMPANY = "company";

    static LetterTarget resolve(TenancyApi tenancy, UUID tenantId, String target) {
        if (COMPANY.equalsIgnoreCase(target)) {
            return new LetterTarget(null, null);
        }
        UUID branchId;
        try {
            branchId = UUID.fromString(target);
        } catch (IllegalArgumentException e) {
            throw new DocumentException.RequestRejected("INVALID_PARAMETER", "Use 'company' or a branch id.");
        }
        String name = tenancy.branchNameFor(branchId, tenantId).orElseThrow(() ->
                new DocumentException.NotFound("BRANCH_NOT_FOUND", "Branch not found."));
        return new LetterTarget(branchId, name);
    }

    String source() {
        return branchId == null ? COMPANY : "branch";
    }
}
