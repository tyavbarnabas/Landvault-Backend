package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.AddressResponse;
import com.techcomfort.landvaultbackend.identity.dto.SaveAddressRequest;
import com.techcomfort.landvaultbackend.identity.internal.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The buyer's own postal address — see AGENTS.md, "A buyer's address". */
@RestController
@RequestMapping("/api/me/address")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_SESSION)
public class AddressController {

    private final AddressService addressService;

    @Operation(
            summary = "My address",
            description = "Requires `client.checkout.reserve`. 204 when none has been given yet. The same "
                    + "address is on the sign-in response as `user.address`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The address"),
            @ApiResponse(responseCode = "204", description = "No address yet", content = @Content())
    })
    @GetMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<AddressResponse> get() {
        return addressService.get(currentUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "Save my address",
            description = "Requires `client.checkout.reserve`. Replaces the whole address. Needed before "
                    + "opening a purchase (`ADDRESS_REQUIRED` otherwise); each purchase keeps a copy taken "
                    + "when it was opened, so changing it later doesn't change a purchase or letter already "
                    + "made. For a Nigerian address `state` must be one of the 36 states or the FCT and is "
                    + "stored as its canonical name; elsewhere it's free text. `country` may differ from the "
                    + "account's country of residence.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The address as saved"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`, `UNKNOWN_STATE` or "
                    + "`UNKNOWN_COUNTRY`, with `fieldErrors`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<AddressResponse> save(@Valid @RequestBody SaveAddressRequest request) {
        return ResponseEntity.ok(addressService.save(currentUserId(), request));
    }

    private UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
