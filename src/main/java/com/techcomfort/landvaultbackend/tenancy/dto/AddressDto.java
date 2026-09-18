package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressDto(@NotBlank String street, @NotBlank String city, @NotBlank String state) {
}
