package com.techcomfort.landvaultbackend.inventory.dto;

import jakarta.validation.constraints.Size;

/** Approve or reject a boundary correction. A note is required to reject, optional to approve. */
public record BoundaryDecisionRequest(@Size(max = 500) String note) {
}
