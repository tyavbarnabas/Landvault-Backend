package com.techcomfort.landvaultbackend.marketplace.dto;

/** The selling branch's office, as the developer entered it. Any field may be null. */
public record BranchOfficeDto(String street, String city, String state, String phone, String email) {
}
