package com.techcomfort.landvaultbackend.marketplace.dto;

/**
 * Who is selling, as "branch · company" (MP-7), matching the frontend's
 * {@code Seller}. Names only: no ids, contacts or anything operational.
 * {@code branchName} is null only if the estate's branch no longer exists.
 */
public record SellerDto(String branchName, String companyName) {
}
