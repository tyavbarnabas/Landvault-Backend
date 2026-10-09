package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.BankDto;
import com.techcomfort.landvaultbackend.payments.dto.CompanySettlementDto;
import com.techcomfort.landvaultbackend.payments.dto.SettlementAccountDto;
import com.techcomfort.landvaultbackend.payments.dto.SubmitSettlementAccountRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.SettlementAccountService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** TR-1/TR-2, the company's side: the account LandVault pays it to. See {@link SettlementAccountService}. */
@RestController
@RequestMapping("/api/portal/settlement")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_SETTLEMENT)
public class PortalSettlementController {

    private final SettlementAccountService settlement;

    @Operation(summary = "Banks Paystack can pay to",
            description = "Requires `portal.settlement.manage`. Submit an account with one of these `code`s.")
    @GetMapping("/banks")
    @PreAuthorize("hasAuthority('portal.settlement.manage')")
    public ResponseEntity<List<BankDto>> banks() {
        return ResponseEntity.ok(settlement.banks());
    }

    @Operation(summary = "Your company's payout accounts",
            description = """
                    Requires `portal.settlement.manage` (Executive Director), company-wide. `approved` is the \\
                    only account ever paid to; `pending` waits for LandVault; `history` keeps every submission.""")
    @GetMapping("/account")
    @PreAuthorize("hasAuthority('portal.settlement.manage')")
    public ResponseEntity<CompanySettlementDto> account() {
        return ResponseEntity.ok(settlement.forCompany());
    }

    @Operation(summary = "Submit a payout account",
            description = """
                    Requires `portal.settlement.manage`, company-wide. Send only the bank `code` and the 10-digit \\
                    account number — **LandVault asks the bank for the account name**. Saved as `pending`; every \\
                    Executive Director is emailed; nothing is paid to it until a LandVault Super Admin approves. \\
                    The current approved account keeps receiving payouts meanwhile.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Submitted, pending approval"),
            @ApiResponse(responseCode = "400", description = "`UNKNOWN_BANK`, `ACCOUNT_NOT_RESOLVED`, `SETTLEMENT_ACCOUNT_UNCHANGED`", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`SETTLEMENT_REQUIRES_COMPANY_WIDE_SCOPE`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`SETTLEMENT_ACCOUNT_PENDING` — withdraw the waiting one first", content = @Content())
    })
    @PostMapping("/account")
    @PreAuthorize("hasAuthority('portal.settlement.manage')")
    public ResponseEntity<SettlementAccountDto> submit(@Valid @RequestBody SubmitSettlementAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(settlement.submit(request.bankCode(), request.accountNumber()));
    }

    @Operation(summary = "Withdraw a pending payout account",
            description = "Requires `portal.settlement.manage`. Only a `pending` submission can be withdrawn.")
    @PostMapping("/account/{accountId}/withdraw")
    @PreAuthorize("hasAuthority('portal.settlement.manage')")
    public ResponseEntity<SettlementAccountDto> withdraw(@PathVariable UUID accountId) {
        return ResponseEntity.ok(settlement.withdraw(accountId));
    }
}
