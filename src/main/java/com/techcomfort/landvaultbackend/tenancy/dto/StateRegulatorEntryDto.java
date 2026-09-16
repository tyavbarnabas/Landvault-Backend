package com.techcomfort.landvaultbackend.tenancy.dto;

import java.util.UUID;

public record StateRegulatorEntryDto(UUID id, String state, String regulatorName, String regNumber, UUID documentId) {
}
