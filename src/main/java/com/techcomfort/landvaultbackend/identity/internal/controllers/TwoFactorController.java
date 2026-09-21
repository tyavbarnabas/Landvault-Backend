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
public class TwoFactorController {

    private final TwoFactorService twoFactorService;

    @PostMapping("/setup")
    public ResponseEntity<TwoFaSetupResponse> setup(@AuthenticationPrincipal AccessTokenClaims claims) {
        return ResponseEntity.ok(twoFactorService.setup(claims.userId()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<RecoveryCodesResponse> confirm(
            @AuthenticationPrincipal AccessTokenClaims claims, @Valid @RequestBody TwoFaCodeRequest request) {
        return ResponseEntity.ok(twoFactorService.confirm(claims.userId(), request.code()));
    }

    /** Public: the caller is mid-login and holds no token yet, only a challenge. */
    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verify(@Valid @RequestBody TwoFaVerifyRequest request) {
        return ResponseEntity.ok(twoFactorService.verify(request));
    }

    @PostMapping("/disable")
    public ResponseEntity<MessageResponse> disable(
            @AuthenticationPrincipal AccessTokenClaims claims, @Valid @RequestBody TwoFaCodeRequest request) {
        twoFactorService.disable(claims.userId(), request.code());
        return ResponseEntity.ok(new MessageResponse("Two-factor authentication has been turned off."));
    }

    @PostMapping("/recovery-codes/regenerate")
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes(
            @AuthenticationPrincipal AccessTokenClaims claims, @Valid @RequestBody TwoFaCodeRequest request) {
        return ResponseEntity.ok(twoFactorService.regenerateRecoveryCodes(claims.userId(), request.code()));
    }
}
