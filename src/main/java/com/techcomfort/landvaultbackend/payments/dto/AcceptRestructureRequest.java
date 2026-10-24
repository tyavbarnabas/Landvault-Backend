package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Phase 5c: the buyer accepts (or declines) the proposal they name — the one whose letter they read. */
@Schema(name = "RestructureDecisionRequest")
public record AcceptRestructureRequest(@NotNull UUID restructureId) {
}
