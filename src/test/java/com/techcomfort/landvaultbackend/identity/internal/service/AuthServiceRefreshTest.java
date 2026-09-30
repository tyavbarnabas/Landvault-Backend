package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.identity.internal.domain.RefreshToken;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.repository.OtpCodeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RecoveryCodeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.TwoFaChallengeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RefreshTokenRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.security.AccessTokenIssue;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtProperties;
import com.techcomfort.landvaultbackend.identity.internal.security.RefreshTokenProperties;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtService;
import com.techcomfort.landvaultbackend.identity.internal.security.TwoFaProperties;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refresh rotation and theft detection, in isolation from the database —
 * the integration test covers the happy path end-to-end; this targets the
 * two branches that are awkward to provoke reliably against a real DB:
 * presenting an already-revoked token, and losing a rotation race.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private TenancyApi tenancyApi;
    @Mock private OtpCodeRepository otpCodeRepository;
    @Mock private OtpDeliveryService otpDeliveryService;
    @Mock private AuditApi auditApi;
    @Mock private TwoFaChallengeRepository twoFaChallengeRepository;
    @Mock private RecoveryCodeRepository recoveryCodeRepository;

    // 2FA is off for every user in these tests; values are irrelevant but must exist.
    private static final TwoFaProperties TWO_FA_PROPERTIES = new TwoFaProperties(
            "bGFuZHZhdWx0LXRlc3Qtb25seS1rZXktMzJieXRlcyE=", 1, 5, Duration.ofMinutes(15), Duration.ofMinutes(5), 10);

    static final RefreshTokenProperties REFRESH_TOKEN_PROPERTIES = new RefreshTokenProperties(
            "lv_refresh", true, "Strict", "/api/auth", Duration.ofSeconds(10), Duration.ofDays(30));

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("unused-in-this-test", Duration.ofMinutes(15), Duration.ofDays(30));
        OtpProperties otpProperties = new OtpProperties(
                Duration.ofMinutes(10), 5, 3, Duration.ofMinutes(15), "noreply@example.com");
        authService = new AuthService(
                userRepository, roleRepository, permissionRepository, userRoleRepository,
                refreshTokenRepository, passwordEncoder, jwtService, jwtProperties, REFRESH_TOKEN_PROPERTIES, tenancyApi,
                otpCodeRepository, otpDeliveryService, otpProperties, auditApi,
                twoFaChallengeRepository, recoveryCodeRepository, TWO_FA_PROPERTIES,
                // No KYC record for these users: the login response reads
                // "unsubmitted", which is what an absent record means.
                userId -> Optional.empty());
        // @PostConstruct isn't invoked by a plain `new` outside Spring —
        // only refresh() is under test here so dummyPasswordHash being
        // unset wouldn't currently bite, but call it anyway so this stays
        // true if a login()-focused test gets added to this class later.
        authService.initDummyPasswordHash();
    }

    private static RefreshToken activeToken(UUID userId) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tokenHash("irrelevant-hash")
                .expiresAt(Instant.now().plus(Duration.ofDays(10)))
                .build();
    }

    @Test
    void rotatesAnActiveTokenAndIssuesANewPair() {
        UUID userId = UUID.randomUUID();
        RefreshToken existing = activeToken(userId);
        User user = User.builder().id(userId).firstName("A").lastName("B").email("a@b.com").build();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> {
            RefreshToken rt = inv.getArgument(0);
            rt.setId(UUID.randomUUID());
            return rt;
        });
        when(refreshTokenRepository.rotateIfActive(eq(existing.getId()), any(), any())).thenReturn(1);
        when(userRoleRepository.findByUserId(userId)).thenReturn(List.of());
        when(jwtService.issueAccessToken(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new AccessTokenIssue("new-access-token", Instant.now().plus(Duration.ofMinutes(15))));

        RefreshResult result = authService.refresh("whatever-raw-token");

        assertThat(result.response().token()).isEqualTo("new-access-token");
        assertThat(result.response().user().email()).isEqualTo("a@b.com");
        assertThat(result.refreshToken()).isNotBlank();
        verify(refreshTokenRepository, never()).delete(any());
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
    }

    /** A token ended by logout/reset/password change: refused, but nobody else is signed out. */
    @Test
    void aTokenEndedWithoutRotationIsRefusedWithoutRevokingTheFamily() {
        UUID userId = UUID.randomUUID();
        RefreshToken ended = activeToken(userId);
        ended.setRevokedAt(Instant.now().minus(Duration.ofMinutes(1)));

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> authService.refresh("logged-out-token"))
                .isInstanceOf(AuthException.InvalidRefreshToken.class);

        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void presentingAnAlreadyRotatedTokenRevokesTheWholeFamily() {
        UUID userId = UUID.randomUUID();
        RefreshToken revoked = activeToken(userId);
        revoked.setRevokedAt(Instant.now().minus(Duration.ofMinutes(1)));
        revoked.setReplacedBy(UUID.randomUUID());

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> authService.refresh("stolen-token"))
                .isInstanceOf(AuthException.InvalidRefreshToken.class);

        verify(refreshTokenRepository).findByUserIdAndRevokedAtIsNull(userId);
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).rotateIfActive(any(), any(), any());
    }

    @Test
    void losingTheRotationRaceCleansUpAndRevokesTheFamilyInsteadOfForkingIt() {
        UUID userId = UUID.randomUUID();
        RefreshToken existing = activeToken(userId);
        User user = User.builder().id(userId).firstName("A").lastName("B").email("a@b.com").build();
        UUID newTokenId = UUID.randomUUID();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> {
            RefreshToken rt = inv.getArgument(0);
            rt.setId(newTokenId);
            return rt;
        });
        // Someone else won the race for this token, by rotating it.
        when(refreshTokenRepository.rotateIfActive(eq(existing.getId()), any(), any())).thenReturn(0);
        when(refreshTokenRepository.countByIdAndReplacedByIsNotNull(existing.getId())).thenReturn(1L);
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> authService.refresh("raced-token"))
                .isInstanceOf(AuthException.InvalidRefreshToken.class);

        ArgumentCaptor<RefreshToken> deleted = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).delete(deleted.capture());
        assertThat(deleted.getValue().getId()).isEqualTo(newTokenId);
        verify(refreshTokenRepository).findByUserIdAndRevokedAtIsNull(userId);
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void expiredTokenIsRejectedWithoutTouchingTheFamily() {
        UUID userId = UUID.randomUUID();
        RefreshToken expired = activeToken(userId);
        expired.setExpiresAt(Instant.now().minus(Duration.ofSeconds(1)));

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("expired-token"))
                .isInstanceOf(AuthException.InvalidRefreshToken.class);

        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
        verify(refreshTokenRepository, never()).rotateIfActive(any(), any(), any());
    }

    @Test
    void refreshIsRejectedWhenTenantIsNotActive() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        RefreshToken existing = activeToken(userId);
        User user = User.builder().id(userId).firstName("A").lastName("B").email("a@b.com").build();
        user.setTenantId(tenantId);

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenancyApi.isTenantActive(tenantId)).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh("whatever-raw-token"))
                .isInstanceOf(AuthException.TenantNotActive.class);

        // Rejected before any write — the presented token is left exactly
        // as it was, not rotated or revoked.
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).rotateIfActive(any(), any(), any());
    }

    @Test
    void unknownTokenHashIsRejected() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("never-issued"))
                .isInstanceOf(AuthException.InvalidRefreshToken.class);
    }
    @Test
    void noCookieIsReportedAsMissingRatherThanInvalid() {
        assertThatThrownBy(() -> authService.refresh(null))
                .isInstanceOf(AuthException.MissingRefreshToken.class);
        assertThatThrownBy(() -> authService.refresh("  "))
                .isInstanceOf(AuthException.MissingRefreshToken.class);
        verify(refreshTokenRepository, never()).findByTokenHash(any());
    }

    /** A second tab presenting the token another tab just rotated: a sibling, not a family revoke. */
    @Test
    void aJustRotatedTokenWithALiveSuccessorGetsASiblingInsteadOfRevokingTheFamily() {
        UUID userId = UUID.randomUUID();
        RefreshToken rotated = activeToken(userId);
        rotated.setRevokedAt(Instant.now().minusSeconds(2));
        rotated.setReplacedBy(UUID.randomUUID());
        User user = User.builder().id(userId).firstName("A").lastName("B").email("a@b.com").build();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(rotated));
        when(refreshTokenRepository.countRotatedSinceWithLiveSuccessor(eq(rotated.getId()), any())).thenReturn(1L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRoleRepository.findByUserId(userId)).thenReturn(List.of());
        when(jwtService.issueAccessToken(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new AccessTokenIssue("sibling-access-token", Instant.now().plus(Duration.ofMinutes(15))));

        RefreshResult result = authService.refresh("second-tab-token");

        assertThat(result.response().token()).isEqualTo("sibling-access-token");
        assertThat(result.refreshToken()).isNotBlank();
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
        // Already rotated: nothing to swap, and the chain is left as the winner wrote it.
        verify(refreshTokenRepository, never()).rotateIfActive(any(), any(), any());
    }

    /** Two tabs at the same instant: the loser of the swap keeps its token when inside the window. */
    @Test
    void losingTheRotationRaceInsideTheGraceWindowKeepsTheSibling() {
        UUID userId = UUID.randomUUID();
        RefreshToken existing = activeToken(userId);
        User user = User.builder().id(userId).firstName("A").lastName("B").email("a@b.com").build();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenRepository.rotateIfActive(eq(existing.getId()), any(), any())).thenReturn(0);
        when(refreshTokenRepository.countRotatedSinceWithLiveSuccessor(eq(existing.getId()), any())).thenReturn(1L);
        when(userRoleRepository.findByUserId(userId)).thenReturn(List.of());
        when(jwtService.issueAccessToken(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new AccessTokenIssue("access", Instant.now().plus(Duration.ofMinutes(15))));

        assertThat(authService.refresh("simultaneous-token").refreshToken()).isNotBlank();

        verify(refreshTokenRepository, never()).delete(any());
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
    }

    /** The grace path is not a side door past the tenant-status gate. */
    @Test
    void theGracePathStillRefusesASuspendedTenantsStaffBeforeWritingAnything() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        RefreshToken rotated = activeToken(userId);
        rotated.setRevokedAt(Instant.now().minusSeconds(2));
        rotated.setReplacedBy(UUID.randomUUID());
        User user = User.builder().id(userId).firstName("A").lastName("B").email("a@b.com").build();
        user.setTenantId(tenantId);

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(rotated));
        when(refreshTokenRepository.countRotatedSinceWithLiveSuccessor(eq(rotated.getId()), any())).thenReturn(1L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenancyApi.isTenantActive(tenantId)).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh("second-tab-token"))
                .isInstanceOf(AuthException.TenantNotActive.class);

        verify(refreshTokenRepository, never()).save(any());
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyBoolean(), any(), any());
    }
    /** Login refused a suspended account; refresh used not to look. Refused before any write. */
    @Test
    void aSuspendedAccountCannotRefreshAndItsTokenIsLeftUntouched() {
        UUID userId = UUID.randomUUID();
        RefreshToken existing = activeToken(userId);
        User user = User.builder().id(userId).firstName("A").lastName("B").email("a@b.com")
                .status(UserStatus.SUSPENDED).build();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh("whatever-raw-token"))
                .isInstanceOf(AuthException.AccountNotActive.class);

        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).rotateIfActive(any(), any(), any());
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyBoolean(), any(), any());
    }
}
