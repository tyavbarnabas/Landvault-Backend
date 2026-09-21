package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.AuthUserResponse;
import com.techcomfort.landvaultbackend.identity.dto.ForgotPasswordRequest;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.dto.ResetPasswordRequest;
import com.techcomfort.landvaultbackend.identity.internal.enums.OtpChannel;
import com.techcomfort.landvaultbackend.identity.internal.enums.OtpPurpose;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import com.techcomfort.landvaultbackend.identity.internal.domain.OtpCode;
import com.techcomfort.landvaultbackend.identity.internal.domain.RefreshToken;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.domain.UserRole;
import com.techcomfort.landvaultbackend.identity.dto.TwoFactorChallengeResponse;
import com.techcomfort.landvaultbackend.identity.internal.domain.TwoFaChallenge;
import com.techcomfort.landvaultbackend.identity.internal.repository.OtpCodeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RecoveryCodeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RefreshTokenRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.TwoFaChallengeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.security.AccessTokenIssue;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtProperties;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtService;
import com.techcomfort.landvaultbackend.identity.internal.security.RoleClaim;
import com.techcomfort.landvaultbackend.identity.internal.security.TwoFaProperties;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Registration, login, refresh and password reset — the business logic
 * behind {@code AuthController}. See AGENTS.md for the token-lifetime,
 * rotation, tenant-never-client-supplied and neutral-response rules this
 * implements.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(OtpProperties.class)
public class AuthService {

    private static final Set<String> PLATFORM_STAFF_ROLE_CODES = Set.of("super_admin", "platform_moderator", "compliance_officer");
    private static final String BUYER_ROLE_CODE = "buyer";
    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final TenancyApi tenancyApi;
    private final OtpCodeRepository otpCodeRepository;
    private final OtpDeliveryService otpDeliveryService;
    private final OtpProperties otpProperties;
    private final AuditApi auditApi;
    private final TwoFaChallengeRepository twoFaChallengeRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final TwoFaProperties twoFaProperties;

    // Precomputed once at startup, not per login attempt — see login()'s
    // timing-safe-failure comment. Not `final`/constructor-assigned: it's
    // derived from passwordEncoder rather than injected directly, which
    // @RequiredArgsConstructor can't express — @PostConstruct runs once
    // all the fields above are wired, same pattern as JwtService's own
    // startup check.
    private String dummyPasswordHash;

    @PostConstruct
    void initDummyPasswordHash() {
        this.dummyPasswordHash = passwordEncoder.encode("timing-safe-comparison-dummy");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new AuthException.EmailAlreadyRegistered();
        }

        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .phone(request.phone())
                .passwordHash(passwordEncoder.encode(request.password()))
                .country(request.country())
                .currency(request.currency())
                .status(UserStatus.PENDING_VERIFICATION)
                .twoFaEnabled(false)
                .build();
        // TODO: OTP / contact confirmation is out of scope for this slice —
        // the account is created straight into PENDING_VERIFICATION and
        // stays there until that flow exists.
        user = userRepository.save(user);

        Role buyerRole = roleRepository.findByCode(BUYER_ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException("Seeded '" + BUYER_ROLE_CODE + "' role is missing"));
        UserRole assignment = UserRole.builder()
                .user(user)
                .roleId(buyerRole.getId())
                .build();
        userRoleRepository.save(assignment);

        return issueAuthResponse(user, List.of(assignment));
    }

    @Transactional
    public LoginResult login(LoginRequest request) {
        var maybeUser = userRepository.findByEmailIgnoreCase(request.email());

        // Timing-safe failure: always run exactly one BCrypt comparison,
        // against the real hash if the user exists or a fixed dummy hash
        // if not, so "unknown email" and "wrong password" take the same
        // time — an attacker can't use response latency to enumerate
        // accounts. Comparing error *messages* alone doesn't close this;
        // the comparison itself has to actually happen either way.
        String hashToCheck = maybeUser.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (maybeUser.isEmpty() || !passwordMatches) {
            throw new AuthException.InvalidCredentials();
        }
        User user = maybeUser.get();

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DEACTIVATED) {
            throw new AuthException.AccountNotActive(user.getStatus());
        }
        // Tenant staff only (buyers/platform staff have no tenantId) — see
        // AGENTS.md's session-revocation note (tenancy slice B2). Blocks
        // issuing a fresh session; an already-issued access token still
        // rides out its own 15-minute lifetime regardless, same accepted
        // trade-off as every other permission change in this system.
        if (user.getTenantId() != null && !tenancyApi.isTenantActive(user.getTenantId())) {
            throw new AuthException.TenantNotActive();
        }

        // Second factor, when the account has one CONFIRMED. Credentials
        // alone stop here: no access token, no refresh token, nothing that
        // can call a protected endpoint. A challenge that could be exchanged
        // for access on its own would make 2FA decorative. See AGENTS.md.
        if (Boolean.TRUE.equals(user.getTwoFaEnabled()) && user.getTwoFaConfirmedAt() != null) {
            return LoginResult.pendingTwoFactor(issueTwoFactorChallenge(user));
        }

        user.setLastLoginAt(Instant.now());
        // Explicit, not relying on dirty checking — matches register()'s
        // pattern, and keeps this correct even if the method's transaction
        // boundary ever changes (e.g. to read-only).
        userRepository.save(user);

        List<UserRole> assignments = userRoleRepository.findByUserId(user.getId());
        return LoginResult.completed(issueAuthResponse(user, assignments));
    }

    /**
     * Mints the pending-login handle. Stored (hashed) rather than issued as a
     * self-contained signed token, because it must be single-use and a signed
     * token is replayable until it expires — see {@code TwoFaChallenge}.
     */
    private TwoFactorChallengeResponse issueTwoFactorChallenge(User user) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiresAt = Instant.now().plus(twoFaProperties.challengeTtl());

        // OtpCodes.hash, not hashRefreshToken: TwoFactorService looks the
        // challenge up with the same helper, so both sides are guaranteed to
        // agree rather than relying on two implementations staying identical.
        twoFaChallengeRepository.save(TwoFaChallenge.builder()
                .userId(user.getId())
                .tokenHash(OtpCodes.hash(rawToken))
                .expiresAt(expiresAt)
                .build());

        log.info("Login for user {} awaiting second factor", user.getId());
        return TwoFactorChallengeResponse.of(rawToken, expiresAt);
    }

    // noRollbackFor is load-bearing: the theft-detection and lost-race
    // branches below deliberately write (revoke the token family / delete
    // an orphaned token) and THEN throw AuthException.InvalidRefreshToken
    // to fail this request. Spring's default @Transactional behavior rolls
    // back on any unchecked exception, which would silently undo exactly
    // the revocation those branches exist to make stick.
    @Transactional(noRollbackFor = AuthException.InvalidRefreshToken.class)
    public RefreshResponse refresh(RefreshRequest request) {
        String hash = hashRefreshToken(request.refreshToken());
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(AuthException.InvalidRefreshToken::new);

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthException.InvalidRefreshToken();
        }
        if (existing.getRevokedAt() != null) {
            // A revoked token was presented again — theft signal. Revoke
            // the whole family and force re-login. See AGENTS.md.
            revokeTokenFamily(existing.getUserId());
            throw new AuthException.InvalidRefreshToken();
        }

        User user = userRepository.findById(existing.getUserId())
                .orElseThrow(AuthException.InvalidRefreshToken::new);
        // Same tenant-status gate as login() — checked before any write
        // below, so a rejection here leaves the presented refresh token
        // exactly as it was (not rotated, not revoked). See AGENTS.md.
        if (user.getTenantId() != null && !tenancyApi.isTenantActive(user.getTenantId())) {
            throw new AuthException.TenantNotActive();
        }

        RawRefreshToken newRefresh = issueRefreshToken(user.getId());
        RefreshToken savedNewToken = refreshTokenRepository.save(newRefresh.entity());

        int rotated = refreshTokenRepository.rotateIfActive(existing.getId(), Instant.now(), savedNewToken.getId());
        if (rotated == 0) {
            // Lost a concurrent rotation race for the same token — someone
            // else (or another in-flight request) already rotated it.
            // Don't leave our freshly issued token as an orphaned,
            // divergent branch of the family.
            refreshTokenRepository.delete(savedNewToken);
            revokeTokenFamily(existing.getUserId());
            throw new AuthException.InvalidRefreshToken();
        }

        List<UserRole> assignments = userRoleRepository.findByUserId(user.getId());
        RoleAssignmentContext ctx = loadContext(assignments);
        AccessTokenIssue accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), user.getTenantId(), ctx.platformStaff(), ctx.roleClaims(), ctx.permissions());

        return new RefreshResponse(accessToken.token(), newRefresh.rawToken());
    }

    /**
     * Issues a password-reset code, or silently does nothing — the caller
     * cannot tell which. Three separate branches here return without
     * generating anything: an unknown email, an account over its request
     * rate limit, and (implicitly) any other reason there is nothing to
     * send. Every one of them produces the same response the success path
     * does, because a distinguishable response would let an attacker
     * enumerate which addresses are registered. See AGENTS.md.
     */
    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        var maybeUser = userRepository.findByEmailIgnoreCase(request.email());
        if (maybeUser.isEmpty()) {
            return;
        }
        User user = maybeUser.get();

        Instant now = Instant.now();
        long recentRequests = otpCodeRepository.countByUserIdAndPurposeAndCreatedAtAfter(
                user.getId(), OtpPurpose.PASSWORD_RESET, now.minus(otpProperties.rateLimitWindow()));
        if (recentRequests >= otpProperties.rateLimitMaxRequests()) {
            // Nothing generated, nothing sent — otherwise this endpoint is a
            // free way to flood someone's inbox and run up delivery costs.
            log.info("Password reset request rate limit reached for user {}; no code generated", user.getId());
            return;
        }

        // Never two valid codes at once: requesting again supersedes the
        // outstanding code rather than adding a second usable one.
        List<OtpCode> outstanding = otpCodeRepository.findByUserIdAndPurposeAndConsumedAtIsNull(
                user.getId(), OtpPurpose.PASSWORD_RESET);
        outstanding.forEach(code -> code.setConsumedAt(now));
        otpCodeRepository.saveAll(outstanding);

        String rawCode = OtpCodes.generate();
        OtpCode otpCode = OtpCode.builder()
                .userId(user.getId())
                .codeHash(OtpCodes.hash(rawCode))
                .purpose(OtpPurpose.PASSWORD_RESET)
                .channel(OtpChannel.EMAIL)
                // Captured now, not re-read at verify time — see OtpCode.
                .destination(user.getEmail())
                .expiresAt(now.plus(otpProperties.codeTtl()))
                .attemptCount(0)
                .build();
        otpCodeRepository.save(otpCode);

        // The raw code leaves this method exactly once, to be delivered, and
        // is never logged or persisted here — only its hash was stored above.
        otpDeliveryService.send(otpCode.getDestination(), rawCode, otpCode.getChannel());
        log.info("Password reset code issued for user {} via {}", user.getId(), otpCode.getChannel());
    }

    /**
     * Verifies a reset code and replaces the password. Every failure throws
     * the same {@link AuthException.InvalidOrExpiredResetCode} — see that
     * class for why the reasons are not distinguished.
     * <p>
     * {@code noRollbackFor} is load-bearing, exactly as it is on
     * {@link #refresh}: the wrong-code branch below increments
     * {@code attemptCount} and <em>then</em> throws to fail the request.
     * Spring's default rollback-on-unchecked-exception would silently undo
     * every increment, leaving the attempt limit permanently at zero and the
     * brute-force protection (PR-4) inert — a million-guess code with no
     * working limiter.
     */
    @Transactional(noRollbackFor = AuthException.InvalidOrExpiredResetCode.class)
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(AuthException.InvalidOrExpiredResetCode::new);

        OtpCode code = otpCodeRepository
                .findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId(), OtpPurpose.PASSWORD_RESET)
                .orElseThrow(AuthException.InvalidOrExpiredResetCode::new);

        if (code.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthException.InvalidOrExpiredResetCode();
        }
        if (code.getAttemptCount() >= otpProperties.maxAttempts()) {
            throw new AuthException.InvalidOrExpiredResetCode();
        }
        if (!OtpCodes.matches(request.code(), code.getCodeHash())) {
            code.setAttemptCount(code.getAttemptCount() + 1);
            otpCodeRepository.save(code);
            throw new AuthException.InvalidOrExpiredResetCode();
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Terminal: a used code can never be reused, even while it would
        // otherwise still be inside its expiry window.
        code.setConsumedAt(Instant.now());
        otpCodeRepository.save(code);

        // Stops new sessions being minted. Already-issued access tokens are
        // NOT severed — they ride out their remaining lifetime (up to the
        // 15-minute TTL), the same accepted trade-off as every other
        // permission change here. See AGENTS.md; don't describe this as
        // instant revocation.
        revokeTokenFamily(user.getId());

        auditApi.record(AuditEntryRequest.of(
                user.getId(), "auth.password_reset_completed", "user", user.getId(), user.getTenantId(),
                "Password reset via one-time code; refresh tokens revoked."));

        log.info("Password reset completed for user {}; refresh tokens revoked", user.getId());
    }

    // Package-private: TwoFactorService completes a two-step login by calling
    // this once the second factor verifies. Not public — issuing tokens stays
    // inside this module's service package.
    AuthResponse issueAuthResponse(User user, List<UserRole> assignments) {
        RoleAssignmentContext ctx = loadContext(assignments);
        AccessTokenIssue accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), user.getTenantId(), ctx.platformStaff(), ctx.roleClaims(), ctx.permissions());

        RawRefreshToken refresh = issueRefreshToken(user.getId());
        refreshTokenRepository.save(refresh.entity());

        boolean twoFaConfirmed = Boolean.TRUE.equals(user.getTwoFaEnabled()) && user.getTwoFaConfirmedAt() != null;
        // Only query when 2FA is actually on — no extra round trip for the
        // overwhelming majority of logins.
        long recoveryCodesRemaining = twoFaConfirmed
                ? recoveryCodeRepository.countByUserIdAndConsumedAtIsNull(user.getId())
                : 0L;

        AuthUserResponse userResponse = new AuthUserResponse(
                user.getFirstName() + " " + user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getCountry(),
                user.getCurrency(),
                "unsubmitted",
                "NG".equalsIgnoreCase(user.getCountry()) ? "local" : "diaspora",
                twoFaConfirmed,
                Boolean.TRUE.equals(user.getMustChangePassword()),
                // Mandatory for platform staff, surfaced rather than enforced
                // at login — see AuthUserResponse and AGENTS.md.
                ctx.platformStaff() && !twoFaConfirmed,
                recoveryCodesRemaining,
                ctx.superAdmin() ? "super_admin" : "client",
                ctx.permissions());

        return new AuthResponse(userResponse, accessToken.token(), refresh.rawToken());
    }

    /**
     * Two queries (roles, then their permission codes) covering both the
     * JWT claims and the user-facing response — deliberately not one per
     * role. A third round trip (the caller's own userRoleRepository.findByUserId)
     * happens before this is called. Three fixed queries per login is
     * acceptable at current volume and not worth chasing further; this is
     * the most frequently hit authenticated-adjacent path, so it's worth
     * having stayed fixed-count rather than growing with role count.
     */
    private RoleAssignmentContext loadContext(List<UserRole> assignments) {
        List<UUID> roleIds = assignments.stream().map(UserRole::getRoleId).distinct().toList();
        List<Role> roles = roleRepository.findAllById(roleIds);
        Map<UUID, Role> rolesById = roles.stream().collect(Collectors.toMap(Role::getId, r -> r));

        List<String> permissions = permissionRepository.findCodesByRoleIdIn(roleIds).stream()
                .distinct()
                .sorted()
                .toList();

        boolean platformStaff = roles.stream().anyMatch(r -> PLATFORM_STAFF_ROLE_CODES.contains(r.getCode()));
        boolean superAdmin = roles.stream().anyMatch(r -> SUPER_ADMIN_ROLE_CODE.equals(r.getCode()));

        // Emitted verbatim from Role.code — no case transformation. The
        // adjacent `permissions` claim already passes its slugs through
        // as-is, so a role claim can be compared directly against seeded
        // Role.code values with nothing to remember in between.
        List<RoleClaim> roleClaims = assignments.stream()
                .map(a -> new RoleClaim(rolesById.get(a.getRoleId()).getCode(), a.getScopedBranchId()))
                .toList();

        return new RoleAssignmentContext(permissions, platformStaff, superAdmin, roleClaims);
    }

    private void revokeTokenFamily(UUID userId) {
        Instant now = Instant.now();
        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        active.forEach(rt -> rt.setRevokedAt(now));
        refreshTokenRepository.saveAll(active);
    }

    private RawRefreshToken issueRefreshToken(UUID userId) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken entity = RefreshToken.builder()
                .userId(userId)
                .tokenHash(hashRefreshToken(raw))
                .expiresAt(Instant.now().plus(jwtProperties.refreshTokenTtl()))
                .build();

        return new RawRefreshToken(raw, entity);
    }

    // Deterministic (unlike BCrypt) so a presented token can be looked up
    // by hash in one query. Fine here because a refresh token is already a
    // 256-bit random secret, not a low-entropy human password — there's no
    // brute-force-guessing concern a slow salted hash would defend against.
    private static String hashRefreshToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record RoleAssignmentContext(List<String> permissions, boolean platformStaff, boolean superAdmin, List<RoleClaim> roleClaims) {
    }

    private record RawRefreshToken(String rawToken, RefreshToken entity) {
    }
}
