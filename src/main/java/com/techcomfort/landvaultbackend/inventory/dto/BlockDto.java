package com.techcomfort.landvaultbackend.inventory.dto;

import java.util.UUID;

public record BlockDto(UUID id, UUID estateId, String name, String label) {
}
