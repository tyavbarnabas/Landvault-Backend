package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.AuthUserResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.internal.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import com.techcomfort.landvaultbackend.identity.internal.domain.Permission;
import com.techcomfort.landvaultbackend.identity.internal.domain.RefreshToken;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.RolePermission;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.domain.UserRole;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RefreshTokenRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RolePermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.security.AccessTokenIssue;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtProperties;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtService;
import com.techcomfort.landvaultbackend.identity.internal.security.RoleClaim;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Registration, login and refresh — the business logic behind
 * {@code AuthController}. See AGENTS.md for the token-lifetime, rotation
 * and tenant-never-client-supplied rules this implements.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Set<String> PLATFORM_STAFF_ROLE_CODES = Set.of("super_admin", "platform_moderator", "compliance_officer");
    private static final String BUYER_ROLE_CODE = "buyer";
    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

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
    public AuthResponse login(LoginRequest request) {
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

        // TODO: the frontend's login is two-step (credentials, then OTP).
        // This is the credential step only; the OTP step lands later
        // without needing to change this endpoint's shape.

        user.setLastLoginAt(Instant.now());

        List<UserRole> assignments = userRoleRepository.findByUserId(user.getId());
        return issueAuthResponse(user, assignments);
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

    private AuthResponse issueAuthResponse(User user, List<UserRole> assignments) {
        RoleAssignmentContext ctx = loadContext(assignments);
        AccessTokenIssue accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), user.getTenantId(), ctx.platformStaff(), ctx.roleClaims(), ctx.permissions());

        RawRefreshToken refresh = issueRefreshToken(user.getId());
        refreshTokenRepository.save(refresh.entity());

        AuthUserResponse userResponse = new AuthUserResponse(
                user.getFirstName() + " " + user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getCountry(),
                user.getCurrency(),
                "unsubmitted",
                "NG".equalsIgnoreCase(user.getCountry()) ? "local" : "diaspora",
                Boolean.TRUE.equals(user.getTwoFaEnabled()),
                ctx.superAdmin() ? "super_admin" : "client",
                ctx.permissions());

        return new AuthResponse(userResponse, accessToken.token(), refresh.rawToken());
    }

    /** One batched load (fixed number of queries, not one per role) covering both the JWT claims and the user-facing response. */
    private RoleAssignmentContext loadContext(List<UserRole> assignments) {
        List<UUID> roleIds = assignments.stream().map(UserRole::getRoleId).distinct().toList();
        List<Role> roles = roleRepository.findAllById(roleIds);
        Map<UUID, Role> rolesById = roles.stream().collect(Collectors.toMap(Role::getId, r -> r));

        List<RolePermission> rolePermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        List<UUID> permissionIds = rolePermissions.stream().map(RolePermission::getPermissionId).distinct().toList();
        List<String> permissions = permissionRepository.findAllById(permissionIds).stream()
                .map(Permission::getCode)
                .distinct()
                .sorted()
                .toList();

        boolean platformStaff = roles.stream().anyMatch(r -> PLATFORM_STAFF_ROLE_CODES.contains(r.getCode()));
        boolean superAdmin = roles.stream().anyMatch(r -> SUPER_ADMIN_ROLE_CODE.equals(r.getCode()));

        // Uppercased for the JWT's `roles` claim specifically (matches the
        // task's example shape) — the stored Role.code stays lowercase,
        // this is a wire-format choice, not a rename of the seeded data.
        List<RoleClaim> roleClaims = assignments.stream()
                .map(a -> new RoleClaim(rolesById.get(a.getRoleId()).getCode().toUpperCase(), a.getScopedBranchId()))
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
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record RoleAssignmentContext(List<String> permissions, boolean platformStaff, boolean superAdmin, List<RoleClaim> roleClaims) {
    }

    private record RawRefreshToken(String rawToken, RefreshToken entity) {
    }
}
