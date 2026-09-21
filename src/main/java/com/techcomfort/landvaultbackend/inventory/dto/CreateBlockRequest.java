package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/portal/estates/{id}/blocks} body. Thin by design — see {@code Block}. */
public record CreateBlockRequest(@NotBlank String name, String label) {
}
