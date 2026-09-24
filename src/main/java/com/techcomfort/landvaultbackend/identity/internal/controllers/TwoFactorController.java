package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.MessageResponse;
import com.techcomfort.landvaultbackend.identity.dto.RecoveryCodesResponse;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaCodeRequest;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaSetupResponse;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaVerifyRequest;
import com.techcomfort.landvaultbackend.identity.internal.security.AccessTokenClaims;
import com.techcomfort.landvaultbackend.identity.internal.service.TwoFactorService;
import jakarta.validation.Valid;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-factor setup and management.
 * <p>
 * Only {@code /verify} is public — it completes a login, so by definition the
 * caller has no token yet. Every other route here requires an authenticated
 * session, which is why {@code SecurityConfig} lists the public
 * {@code /api/auth/**} routes explicitly instead of allowing the whole
 * subtree (see the comment there).
 */
@RestController
@RequestMapping("/api/auth/2fa")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_TWO_FACTOR)
public class TwoFactorController {

    private final TwoFactorService twoFactorService;

    @Operation(
            summary = "Start TOTP enrolment (does NOT enable 2FA)",
            description = """
                    Issues a secret and an `otpauth://` URI to render as a QR code.

                    **Two-factor is still off after this call.** It is only switched on by \
                    `POST /api/auth/2fa/confirm`, once the user proves their app holds the secret.

                    That split is not ceremony. Recovery codes are issued at confirmation, so \
                    enabling 2FA at setup time would lock a user out permanently if the pairing \
                    silently failed — a mis-scanned QR, a crashed app — with nothing to recover \
                    with. Calling setup and assuming 2FA is on is the obvious mistake here.

                    Calling setup again before confirming simply issues a new secret.""")
    @ApiResponse(responseCode = "200", description = "Secret and otpauth URI; 2FA remains OFF")
    @PostMapping("/setup")
    public ResponseEntity<TwoFaSetupResponse> setup(@AuthenticationPrincipal AccessTokenClaims claims) {
        return ResponseEntity.ok(twoFactorService.setup(claims.userId()));
    }

    @Operation(
            summary = "Confirm enrolment and switch 2FA on",
            description = """
                    Verifies a code from the authenticator app, enables two-factor, and returns the \
                    **recovery codes**.

                    **The recovery codes are shown exactly once and are never retrievable again.** \
                    They are stored hashed. Without them, a lost phone means an account only manual \
                    database intervention can reach — this is the part of a TOTP implementation \
                    most often skipped, and the one that generates the support load when it is.

                    From here on `POST /api/auth/login` returns a challenge instead of tokens.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "2FA enabled; recovery codes returned once"),
            @ApiResponse(responseCode = "400", description = "Wrong or expired code, or setup was "
                    + "never started", content = @Content())
    })
    @PostMapping("/confirm")
    public ResponseEntity<RecoveryCodesResponse> confirm(
            @AuthenticationPrincipal AccessTokenClaims claims, @Valid @RequestBody TwoFaCodeRequest request) {
        return ResponseEntity.ok(twoFactorService.confirm(claims.userId(), request.code()));
    }

    /** Public: the caller is mid-login and holds no token yet, only a challenge. */
    @Operation(
            summary = "Complete a login with a second factor",
            description = """
                    Exchanges the `challengeId` from login, plus a TOTP **or** a recovery code, for \
                    real tokens.

                    Public: the caller has no token yet, which is the whole point.

                    A challenge is single-use and short-lived. A recovery code is consumed and \
                    cannot be reused, and using one writes an audit entry — it means the user lost \
                    device access, which is worth a record. Five failures lock the account for a \
                    period; the lock is time-based, not permanent, because a TOTP user cannot \
                    request a fresh code the way a password-reset user can.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Access and refresh tokens"),
            @ApiResponse(responseCode = "400", description = "Bad, expired or already-used challenge, "
                    + "or a wrong code", content = @Content()),
            @ApiResponse(responseCode = "423", description = "Locked out after repeated failures",
                    content = @Content())
    })
    @SecurityRequirements
    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verify(@Valid @RequestBody TwoFaVerifyRequest request) {
        return ResponseEntity.ok(twoFactorService.verify(request));
    }

    @Operation(
            summary = "Turn 2FA off",
            description = """
                    Requires a valid TOTP or recovery code — **a session alone is deliberately not \
                    enough.** If a hijacked session could strip two-factor, the protection would be \
                    defeated by exactly the attack it exists to prevent.

                    **Platform staff cannot disable it at all** (`TWO_FACTOR_MANDATORY`, 403): they \
                    hold the platform-scope database bypass, the most sensitive credential in the \
                    system. That check runs before the code check, so staff are not invited to keep \
                    guessing at a door that never opens.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "2FA disabled"),
            @ApiResponse(responseCode = "400", description = "Wrong code", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`TWO_FACTOR_MANDATORY` for platform staff",
                    content = @Content())
    })
    @PostMapping("/disable")
    public ResponseEntity<MessageResponse> disable(
            @AuthenticationPrincipal AccessTokenClaims claims, @Valid @RequestBody TwoFaCodeRequest request) {
        twoFactorService.disable(claims.userId(), request.code());
        return ResponseEntity.ok(new MessageResponse("Two-factor authentication has been turned off."));
    }

    @Operation(
            summary = "Issue a fresh set of recovery codes",
            description = """
                    Replaces every existing recovery code and returns the new set **once**.

                    Requires a **TOTP code specifically**, never a recovery code: otherwise one \
                    stale code could mint a whole new set, and a leaked code would be enough to keep \
                    an attacker in indefinitely.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New codes, shown once"),
            @ApiResponse(responseCode = "400", description = "Wrong code, or a recovery code was used "
                    + "where a TOTP code is required", content = @Content())
    })
    @PostMapping("/recovery-codes/regenerate")
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes(
            @AuthenticationPrincipal AccessTokenClaims claims, @Valid @RequestBody TwoFaCodeRequest request) {
        return ResponseEntity.ok(twoFactorService.regenerateRecoveryCodes(claims.userId(), request.code()));
    }
}
