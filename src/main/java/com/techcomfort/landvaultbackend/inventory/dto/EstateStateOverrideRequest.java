package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/admin/estates/{id}/state-override} body. Why is required — it's a decision, and it's kept. */
public record EstateStateOverrideRequest(@NotBlank String reason) {
}
