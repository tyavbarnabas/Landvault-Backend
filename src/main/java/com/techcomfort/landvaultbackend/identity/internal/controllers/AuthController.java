package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.ChangePasswordRequest;
import com.techcomfort.landvaultbackend.identity.dto.ForgotPasswordRequest;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.MessageResponse;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.dto.ResetPasswordRequest;
import com.techcomfort.landvaultbackend.identity.internal.security.OriginGuard;
import com.techcomfort.landvaultbackend.identity.internal.security.RefreshTokenCookies;
import com.techcomfort.landvaultbackend.identity.internal.service.AuthService;
import com.techcomfort.landvaultbackend.identity.internal.service.IssuedSession;
import com.techcomfort.landvaultbackend.identity.internal.service.RefreshResult;
import com.techcomfort.landvaultbackend.identity.internal.service.LoginResult;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * register, login, refresh, logout — the credential step only — plus password
 * reset; see AuthService. The refresh token only ever travels in an
 * {@code HttpOnly} cookie (RefreshTokenCookies), never a body. Every route here is public, but note that
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
    private final RefreshTokenCookies refreshTokenCookies;
    private final OriginGuard originGuard;

    public AuthController(AuthService authService, RefreshTokenCookies refreshTokenCookies, OriginGuard originGuard) {
        this.authService = authService;
        this.refreshTokenCookies = refreshTokenCookies;
        this.originGuard = originGuard;
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
            @ApiResponse(responseCode = "201", description = "Created; the body carries an access "
                    + "token and the user's permission slugs, and the refresh token is set as an "
                    + "HttpOnly cookie — never in the body"),
            @ApiResponse(responseCode = "400", description = "Validation failed; see `fieldErrors`. Or "
                    + "`WEAK_PASSWORD`: the password breaks the password rule (8+ characters, at most 64, letters and a number, not common, not your name or email); `message` says which, `fieldErrors` names the field",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "409", description = "That email already has an account",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        IssuedSession session = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(session.refreshToken()).toString())
                .body(session.response());
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

                    - Two-factor off → `AuthResponse`: access token and user, with the refresh \
                    token set as an `HttpOnly` cookie (never in the body).
                    - Two-factor on → `TwoFactorChallengeResponse`: `twoFactorRequired`, a \
                    `challengeToken` and its expiry, and nothing else. \
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
                    + "`TwoFactorChallengeResponse` — discriminate on `twoFactorRequired`, which is "
                    + "present and true only on the challenge"),
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
        if (result.requiresTwoFactor()) {
            return ResponseEntity.ok(result.challenge());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(result.session().refreshToken()).toString())
                .body(result.session().response());
    }

    @Operation(
            summary = "Exchange the refresh cookie for a new access token",
            description = """
                    **Send no body.** The refresh token lives in an `HttpOnly` cookie scoped to \
                    `/api/auth`; the browser attaches it (`credentials: "include"`). A new access \
                    token **and the current user** come back in the body — the same \
                    `{ user, token }` shape as login — and a new cookie replaces the old one. \
                    **This is how a page load restores a session**: `REFRESH_TOKEN_MISSING` means \
                    nobody is signed in on this browser.

                    **Refresh tokens rotate.** Each call retires the token presented. Presenting a \
                    retired token again **revokes every session the user has** and forces a fresh \
                    login — that is theft detection. The one exception is a short grace window: a \
                    token rotated a few seconds ago whose replacement is still live (a second tab, \
                    a retried request) gets a fresh token instead. Serialise refreshes across tabs \
                    anyway.

                    A missing cookie (`REFRESH_TOKEN_MISSING`) and a bad one \
                    (`INVALID_REFRESH_TOKEN`) are distinct 401s, so a client can tell "never \
                    signed in" from "session ended". A request from an origin outside the allowed \
                    list is refused.

                    A suspended or deactivated account, and a suspended tenant's staff, are refused \
                    here exactly as at login, so a session cannot outlive either by more than the \
                    access token's 15 minutes.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`{ user, token }`; new refresh cookie set"),
            @ApiResponse(responseCode = "401", description = "`REFRESH_TOKEN_MISSING`, or "
                    + "`INVALID_REFRESH_TOKEN` (unknown, expired or already used — if already used "
                    + "outside the grace window, every token for that user is now revoked)",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "403", description = "`ORIGIN_NOT_ALLOWED`, `ACCOUNT_SUSPENDED`, "
                    + "`ACCOUNT_DEACTIVATED` or `TENANT_NOT_ACTIVE`",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(HttpServletRequest httpRequest) {
        originGuard.requireAllowedOrigin(httpRequest);
        RefreshResult result = authService.refresh(refreshTokenCookies.read(httpRequest).orElse(null));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(result.refreshToken()).toString())
                .body(result.response());
    }

    /**
     * Public, cookie-authenticated: it must work with an expired access
     * token. Always 204, and always clears the cookie.
     */
    @Operation(
            summary = "Sign out this device",
            description = """
                    Revokes the refresh token in the cookie and clears the cookie. **Only this \
                    session ends** — the same account signed in elsewhere stays signed in.

                    Needs no access token, so it works after one has expired. Always 204, whether \
                    or not there was a live session to end. An access token already issued rides \
                    out its remaining lifetime, up to 15 minutes; discard it client-side.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Signed out; cookie cleared"),
            @ApiResponse(responseCode = "403", description = "`ORIGIN_NOT_ALLOWED`",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        originGuard.requireAllowedOrigin(httpRequest);
        authService.logout(refreshTokenCookies.read(httpRequest).orElse(null));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear().toString())
                .build();
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
            @ApiResponse(responseCode = "400", description = "`INVALID_OR_EXPIRED_CODE`: one message for every "
                    + "code failure, by design. Or, only once the code is proven, " + "`WEAK_PASSWORD`: the password breaks the password rule (8+ characters, at most 64, letters and a number, not common, not your name or email); `message` says which, `fieldErrors` names the field"
                    + " — the code stays usable, so retry with a better password",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new MessageResponse("Your password has been reset. Sign in with your new password."));
    }

    /**
     * Authenticated: the caller proves who they are with a token and what
     * they know with {@code currentPassword}. Deliberately absent from
     * {@code SecurityConfig}'s public list — unlike reset, this is not a
     * route for someone locked out.
     */
    @Operation(
            summary = "Change your own password",
            description = """
                    Requires a signed-in session **and** the current password.

                    **This is not a reset, and the difference is what it verifies.** A reset proves                     control of a mailbox; this proves knowledge of the password being replaced —                     which is what a change-password screen actually means. It also works on a                     deployment with no mail configured, so a freshly bootstrapped Super Admin can                     retire a temporary credential that has passed through an environment variable                     and a shell history.

                    On success every **other** session's refresh token is revoked, this browser gets \
                    a fresh refresh cookie (so it stays signed in), and `mustChangePassword` is \
                    cleared.

                    **"Sessions revoked" means refresh tokens.** An access token already issued                     rides out its remaining lifetime, up to 15 minutes — the same accepted                     trade-off as password reset and tenant suspension. Do not present this as                     instant severance.

                    The new password must meet the password rule (`WEAK_PASSWORD`), and must differ                     from the current one. Existing passwords are never re-checked at login.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed; other sessions revoked, a fresh "
                    + "refresh cookie set for this one"),
            @ApiResponse(responseCode = "400", description = "`WEAK_PASSWORD`: the password breaks the password rule (8+ characters, at most 64, letters and a number, not common, not your name or email); `message` says which, `fieldErrors` names the field; or the new password is the current one",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "401", description = "The current password is wrong — the "
                    + "same response a bad password gets at login",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    // Overrides the class-level empty @SecurityRequirements: this is the one
    // route here that needs a token, and without this Swagger UI never sends it.
    @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
    @PostMapping("/change-password")
    public ResponseEntity<MessageResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        String thisDevice = authService.changePassword(scope.userId(), request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(thisDevice).toString())
                .body(new MessageResponse("Your password has been changed. Other sessions will need to sign in again."));
    }
}
