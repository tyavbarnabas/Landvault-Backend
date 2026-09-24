package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.inventory.dto.DeclareDefaultTermsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.DeclareFeesRequest;
import com.techcomfort.landvaultbackend.inventory.dto.DeclareRefundTermsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.DefaultTermsDto;
import com.techcomfort.landvaultbackend.inventory.dto.FeeScheduleDto;
import com.techcomfort.landvaultbackend.inventory.dto.RefundTermsDto;
import com.techcomfort.landvaultbackend.inventory.internal.service.EstateDisclosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Declaring the true cost of a listing (FD, RF and DF).
 * <p>
 * <strong>Reads are gated on {@code portal.estates.view}, writes on
 * {@code portal.estates.manage}</strong> — the split those two slugs already
 * exist to make. The task spec put every route here behind {@code manage};
 * that would stop a sales manager reading the fee schedule they are asked
 * about all day, while only two roles ever need to define it.
 */
@RestController
@RequestMapping("/api/portal/estates/{id}")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_ESTATES)
public class PortalEstateDisclosureController {

    private final EstateDisclosureService disclosureService;

    // --- fees ---

    @Operation(
            summary = "This estate's declared fee schedule",
            description = "Requires `portal.estates.view`. `declaredAt` null means nothing has been "
                    + "declared, which blocks publication; an empty `fees` list with a non-null "
                    + "`declaredAt` means there are genuinely no extra charges, which does not.")
    @ApiResponse(responseCode = "200", description = "The current declaration")
    @GetMapping("/fees")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<FeeScheduleDto> fees(@PathVariable UUID id) {
        return ResponseEntity.ok(disclosureService.fees(id));
    }

    @Operation(
            summary = "Declare every charge beyond the land price",
            description = """
                    Requires `portal.estates.manage`. **Required before this estate can be listed.**

                    Writes a new version rather than editing the last — a schedule that can be \
                    quietly revised after a buyer has seen it is not a disclosure.

                    **Declaring an empty list is a valid declaration** and unblocks publication; \
                    saying nothing does not.

                    A fee is either fixed (`amount`) or variable (`amountMin`, `amountMax` and a \
                    `variationBasis` stating why). **Mark a fee mandatory when the buyer has no path \
                    that avoids it** — a charge that applies "only if you build" is unavoidable where \
                    the terms also require building, which is the case in every allocation letter \
                    this was modelled on.

                    The platform does not judge amounts. Nothing here caps, warns on, or flags a \
                    figure as excessive.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The new version"),
            @ApiResponse(responseCode = "400", description = "`INVALID_REQUEST`: a fixed fee with a "
                    + "range, a variable fee with no stated basis for variation, an `other` fee with "
                    + "no label, or a duplicate", content = @Content()),
            @ApiResponse(responseCode = "404", description = "No such estate of yours", content = @Content())
    })
    @PutMapping("/fees")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<FeeScheduleDto> declareFees(
            @PathVariable UUID id, @Valid @RequestBody DeclareFeesRequest request) {
        return ResponseEntity.ok(disclosureService.declareFees(id, request));
    }

    // --- refund terms ---

    @Operation(
            summary = "This estate's refund policy",
            description = "Requires `portal.estates.view`. 404 when none has been declared.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current policy"),
            @ApiResponse(responseCode = "404", description = "Nothing declared yet", content = @Content())
    })
    @GetMapping("/refund-terms")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<RefundTermsDto> refundTerms(@PathVariable UUID id) {
        return disclosureService.refundTerms(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Declare what a buyer gets back if they withdraw",
            description = """
                    Requires `portal.estates.manage`.

                    **State `appliesTo`.** A deduction taken from the full price is a different \
                    figure from one taken from what has actually been paid, and the source documents \
                    this was modelled on were ambiguous about exactly that.

                    Buyers see this computed **in naira**, not as a percentage — "20% administrative \
                    charge" is abstract; "you receive ₦4,800,000, a loss of ₦1,200,000, after about \
                    90 days" is something a person can react to.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The new version"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR` or `INVALID_REQUEST`",
                    content = @Content())
    })
    @PutMapping("/refund-terms")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<RefundTermsDto> declareRefundTerms(
            @PathVariable UUID id, @Valid @RequestBody DeclareRefundTermsRequest request) {
        return ResponseEntity.ok(disclosureService.declareRefundTerms(id, request));
    }

    // --- default terms ---

    @Operation(
            summary = "This estate's default, revocation and transfer terms",
            description = "Requires `portal.estates.view`. 404 when none has been declared.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current terms"),
            @ApiResponse(responseCode = "404", description = "Nothing declared yet", content = @Content())
    })
    @GetMapping("/default-terms")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<DefaultTermsDto> defaultTerms(@PathVariable UUID id) {
        return disclosureService.defaultTerms(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Declare the consequences of falling behind",
            description = """
                    Requires `portal.estates.manage`.

                    **`onRevocationRefund` is required**, because it is the thing real allocation \
                    letters leave out: "your allocation may be revoked" without saying what happens \
                    to money already paid tells a buyer nothing about their exposure.

                    Penalty tiers are declared as percentages and published to buyers as naira \
                    amounts. `developmentDeadlineMonths` is **stored only** — nothing tracks the \
                    clock yet. `transferRequiresConsent` is **disclosed only** — there is no resale \
                    flow to enforce it in.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The new version"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR` or `INVALID_REQUEST`: "
                    + "two penalties for the same month, say", content = @Content())
    })
    @PutMapping("/default-terms")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<DefaultTermsDto> declareDefaultTerms(
            @PathVariable UUID id, @Valid @RequestBody DeclareDefaultTermsRequest request) {
        return ResponseEntity.ok(disclosureService.declareDefaultTerms(id, request));
    }
}
