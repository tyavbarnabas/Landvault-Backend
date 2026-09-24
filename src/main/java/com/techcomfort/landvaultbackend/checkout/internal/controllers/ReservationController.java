package com.techcomfort.landvaultbackend.checkout.internal.controllers;

import com.techcomfort.landvaultbackend.checkout.dto.CreateReservationRequest;
import com.techcomfort.landvaultbackend.checkout.dto.ReservationDto;
import com.techcomfort.landvaultbackend.checkout.internal.service.ReservationService;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Holding a plot while a buyer checks out (RS-1 … RS-4). Always the
 * authenticated buyer's own holds.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_RESERVATIONS)
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(
            summary = "Hold a plot",
            description = """
                    Requires `client.checkout.reserve` **and completed identity verification** — \
                    verification gates buying, not browsing, so this is where a buyer meets it.

                    Takes the plot off the market for 45 minutes. **Holding is not owning**: the \
                    plot is not allocated and nothing is sold until a finance-role human verifies a \
                    payment.

                    **The hold is atomic.** Of two buyers reaching the same plot at the same \
                    instant, exactly one succeeds; the other is told it is no longer available. \
                    That is the single correctness property this endpoint exists for.

                    The estate's publication conditions are re-evaluated here, not trusted from the \
                    browse response — a company suspended, or a boundary conflict raised, since the \
                    page loaded stops the sale.

                    **A hold cannot be extended.** It expires by itself, whether or not the buyer's \
                    browser is still open.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The hold, with its countdown"),
            @ApiResponse(responseCode = "403", description = "`KYC_REQUIRED`: identity verification "
                    + "must be completed first", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLOT_NOT_AVAILABLE`: already held, "
                    + "sold, or not currently listed — the three are deliberately indistinguishable; "
                    + "or `ESTATE_NOT_AVAILABLE` when the listing stopped qualifying",
                    content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<ReservationDto> reserve(@Valid @RequestBody CreateReservationRequest request) {
        ReservationDto reservation = reservationService.reserve(currentUserId(), request.plotId());
        return ResponseEntity.status(HttpStatus.CREATED).body(reservation);
    }

    @Operation(
            summary = "My active holds",
            description = "Requires `client.checkout.reserve`. Only the signed-in buyer's own live "
                    + "holds, soonest to expire first. Expired and released ones are not listed.")
    @ApiResponse(responseCode = "200", description = "Active holds; empty when there are none")
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<List<ReservationDto>> mine() {
        return ResponseEntity.ok(reservationService.activeFor(currentUserId()));
    }

    @Operation(
            summary = "Give up a hold",
            description = """
                    Requires `client.checkout.reserve`. Returns the plot to the pool immediately, \
                    in the exact availability it had before — a development plot does not come back \
                    as an investment one.

                    Only the buyer holding it can release it. **Another buyer's hold returns 404, \
                    not 403**, so an id cannot be probed for existence.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Released"),
            @ApiResponse(responseCode = "404", description = "`RESERVATION_NOT_FOUND`: no such hold "
                    + "of yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`RESERVATION_NOT_ACTIVE`: already "
                    + "expired, released or converted", content = @Content())
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<Void> release(@PathVariable UUID id) {
        reservationService.release(currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
