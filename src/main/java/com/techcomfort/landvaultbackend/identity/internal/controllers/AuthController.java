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
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * register, login, refresh — the credential step only — plus password
 * reset; see AuthService. Every route here is public, but note that
 * {@code /api/auth/**} is <em>not</em> a wildcard in
 * {@code SecurityConfig}: the 2FA slice replaced it with one entry per
 * route, because {@code /api/auth/2fa/setup|confirm|disable} must require a
 * session. Adding a route here means adding it to
 * {@code ALWAYS_PUBLIC_PATHS} too; forgetting makes it require
 * authentication, which is the safe direction to fail.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = OpenApiConfig.TAG_AUTH)
@SecurityRequirements
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(
            summary = "Register a buyer account",
            description = """
                    Creates a buyer and returns tokens immediately — no email verification step exists \
                    yet.

                    **This can only ever create a buyer.** Tenant staff are created by the \
                    tenant-onboarding flow, and the first Super Admin is a deployment step, never a \
                    signup: a self-registering platform administrator would be a serious hole.

                    Email is unique across the whole platform, case-insensitively.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; the response carries an access "
                    + "token, a refresh token and the user's permission slugs"),
            @ApiResponse(responseCode = "400", description = "Validation failed; see `fieldErrors`",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "409", description = "That email already has an account",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
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
    @Operation(
            summary = "Log in (may return a 2FA challenge instead of tokens)",
            description = """
                    **Two different 200 responses, and a client must handle both.**

                    - Two-factor off → `AuthResponse`: access token, refresh token, user.
                    - Two-factor on → `TwoFactorChallengeResponse`: a challenge id and nothing else. \
                    **No tokens are issued at this point.** Exchange the challenge plus a TOTP or \
                    recovery code at `POST /api/auth/2fa/verify`.

                    The two shapes deliberately share no field name, so a client cannot mistake one \
                    for the other. Accounts without 2FA are unaffected.

                    **A wrong password and an unknown email return exactly the same 401**, and take \
                    about the same time (a dummy hash is verified for an unknown address). That is \
                    deliberate: a distinguishable answer tells an attacker which addresses are \
                    registered. Do not "improve" it into a helpful message.

                    A suspended or offboarded tenant's staff are refused here with \
                    `TENANT_NOT_ACTIVE`, and the same check runs again on refresh.

                    The user object carries `mustChangePassword` and `mustSetUpTwoFa`. Neither blocks \
                    logging in — a bootstrapped admin has to be able to sign in to fix them — so the \
                    client is responsible for routing on them.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Either an `AuthResponse` or a "
                    + "`TwoFactorChallengeResponse` — check for a `challengeId` field"),
            @ApiResponse(responseCode = "401", description = "Wrong password, unknown email, or a "
                    + "locked-out account. Identical body in the first two cases",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "403", description = "`TENANT_NOT_ACTIVE`: the caller is staff "
                    + "of a suspended or offboarded company",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request);
        return ResponseEntity.ok(result.requiresTwoFactor() ? result.challenge() : result.authResponse());
    }

    @Operation(
            summary = "Exchange a refresh token for a new access token",
            description = """
                    **Refresh tokens rotate.** Each call issues a new one and revokes the token you \
                    presented, chaining them together. Always store the token this returns; the old \
                    one stops working immediately.

                    **Presenting an already-revoked token revokes the entire family** and forces a \
                    fresh login. That is theft detection, not a bug: a token used after the \
                    legitimate client already rotated past it means somebody has a copy. Concurrent \
                    refreshes of the same token are resolved in the database, so exactly one wins and \
                    the loser triggers the same path — single-flight your refresh calls.

                    A suspended tenant's staff are refused here too, so a session cannot outlive its \
                    company's suspension by more than the access token's 15 minutes.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New access token and new refresh token"),
            @ApiResponse(responseCode = "401", description = "Unknown, expired or already-used token. "
                    + "If already used, every token for that user is now revoked",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
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
    @Operation(
            summary = "Request a password-reset code",
            description = """
                    Sends a six-digit code to the address, valid for 10 minutes.

                    **Always 200, with the same body, whatever happens** — whether the address has an \
                    account, whether it is over its rate limit, whether a code was generated at all. \
                    A helpful "no account found" is a way to enumerate which addresses are \
                    registered. Do not add a branch that varies this response.

                    Rate-limited per account (3 codes per 15 minutes, counting superseded ones, so \
                    asking repeatedly does not reset the limit). Requesting a new code invalidates \
                    the previous one: there is never more than one valid code.""")
    @ApiResponse(responseCode = "200", description = "Always, regardless of whether the address exists")
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok(new MessageResponse("If an account exists for that address, a code has been sent."));
    }

    @Operation(
            summary = "Reset a password using a code",
            description = """
                    Consumes the code and sets the new password.

                    **One error covers every failure** — no code requested, expired, already used, \
                    attempt limit reached, wrong code, no such account — because distinguishing them \
                    leaks both whether an account exists and whether a reset is in flight.

                    Five wrong attempts burn the code permanently; the user must request a new one.

                    A successful reset **revokes every refresh token** for the user, so no existing \
                    session can be renewed. Access tokens already issued still work for up to 15 \
                    minutes — this is not instant severance, and should not be described as such to \
                    a user who thinks they are compromised.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed; all refresh tokens revoked"),
            @ApiResponse(responseCode = "400", description = "One message for every failure mode, by design",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new MessageResponse("Your password has been reset. Sign in with your new password."));
    }
}
