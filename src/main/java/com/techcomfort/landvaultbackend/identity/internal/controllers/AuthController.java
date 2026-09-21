package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.ForgotPasswordRequest;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.MessageResponse;
import com.techcomfort.landvaultbackend.identity.dto.RefreshRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.dto.ResetPasswordRequest;
import com.techcomfort.landvaultbackend.identity.internal.service.AuthService;
import com.techcomfort.landvaultbackend.identity.internal.service.LoginResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * register, login, refresh — the credential step only — plus password
 * reset; see AuthService. Every route here is public: {@code /api/auth/**}
 * is already in {@code SecurityConfig.ALWAYS_PUBLIC_PATHS}, so the two reset
 * endpoints below needed no change there.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /**
     * Returns {@code AuthResponse} when 2FA is off, and a
     * {@code TwoFactorChallengeResponse} — no tokens of any kind — when it's
     * on. The two shapes share no field names, so a client can't mistake one
     * for the other; see that DTO. Behaviour for accounts without 2FA is
     * unchanged.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request);
        return ResponseEntity.ok(result.requiresTwoFactor() ? result.challenge() : result.authResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /**
     * Always 200 with the same body, whether or not the address belongs to
     * an account and whether or not a code was actually generated — see
     * AuthService.requestPasswordReset. Do not add a branch here that varies
     * the response; that would undo the whole point of the endpoint.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok(new MessageResponse("If an account exists for that address, a code has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new MessageResponse("Your password has been reset. Sign in with your new password."));
    }
}
