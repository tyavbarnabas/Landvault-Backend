package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.identity.dto.RefreshRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.internal.domain.RefreshToken;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
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

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("unused-in-this-test", Duration.ofMinutes(15), Duration.ofDays(30));
        OtpProperties otpProperties = new OtpProperties(
                Duration.ofMinutes(10), 5, 3, Duration.ofMinutes(15), "noreply@example.com");
        authService = new AuthService(
                userRepository, roleRepository, permissionRepository, userRoleRepository,
                refreshTokenRepository, passwordEncoder, jwtService, jwtProperties, tenancyApi,
                otpCodeRepository, otpDeliveryService, otpProperties, auditApi,
                twoFaChallengeRepository, recoveryCodeRepository, TWO_FA_PROPERTIES);
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

        RefreshResponse response = authService.refresh(new RefreshRequest("whatever-raw-token"));

        assertThat(response.token()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isNotBlank();
        verify(refreshTokenRepository, never()).delete(any());
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
    }

    @Test
    void presentingAnAlreadyRevokedTokenRevokesTheWholeFamily() {
        UUID userId = UUID.randomUUID();
        RefreshToken revoked = activeToken(userId);
        revoked.setRevokedAt(Instant.now().minus(Duration.ofMinutes(1)));

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("stolen-token")))
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
        // Someone else won the race for this token.
        when(refreshTokenRepository.rotateIfActive(eq(existing.getId()), any(), any())).thenReturn(0);
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("raced-token")))
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

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("expired-token")))
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

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("whatever-raw-token")))
                .isInstanceOf(AuthException.TenantNotActive.class);

        // Rejected before any write — the presented token is left exactly
        // as it was, not rotated or revoked.
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).rotateIfActive(any(), any(), any());
    }

    @Test
    void unknownTokenHashIsRejected() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("never-issued")))
                .isInstanceOf(AuthException.InvalidRefreshToken.class);
    }
}
