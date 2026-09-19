package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.identity.dto.ForgotPasswordRequest;
import com.techcomfort.landvaultbackend.identity.dto.ResetPasswordRequest;
import com.techcomfort.landvaultbackend.identity.internal.domain.OtpCode;
import com.techcomfort.landvaultbackend.identity.internal.domain.RefreshToken;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.enums.OtpChannel;
import com.techcomfort.landvaultbackend.identity.internal.enums.OtpPurpose;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import com.techcomfort.landvaultbackend.identity.internal.repository.OtpCodeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RefreshTokenRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtProperties;
import com.techcomfort.landvaultbackend.identity.internal.security.JwtService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Password reset in isolation from the database. The integration test covers
 * the flow end to end; this targets the branches that are fiddly to provoke
 * against a real clock and a real HTTP client — expiry boundaries, the
 * attempt counter, and the several "silently do nothing" paths whose whole
 * point is that they're indistinguishable from outside.
 */
@ExtendWith(MockitoExtension.class)
class AuthServicePasswordResetTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration CODE_TTL = Duration.ofMinutes(10);

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

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("unused-in-this-test", Duration.ofMinutes(15), Duration.ofDays(30));
        OtpProperties otpProperties = new OtpProperties(
                CODE_TTL, MAX_ATTEMPTS, 3, Duration.ofMinutes(15), "noreply@example.com");
        authService = new AuthService(
                userRepository, roleRepository, permissionRepository, userRoleRepository,
                refreshTokenRepository, passwordEncoder, jwtService, jwtProperties, tenancyApi,
                otpCodeRepository, otpDeliveryService, otpProperties, auditApi);
        authService.initDummyPasswordHash();
    }

    private static User user(UUID id) {
        User user = User.builder().id(id).firstName("Ada").lastName("L").email("ada@example.com").build();
        user.setPasswordHash("old-hash");
        return user;
    }

    private static OtpCode code(UUID userId, String rawCode, Instant expiresAt, int attemptCount) {
        return OtpCode.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .codeHash(OtpCodes.hash(rawCode))
                .purpose(OtpPurpose.PASSWORD_RESET)
                .channel(OtpChannel.EMAIL)
                .destination("ada@example.com")
                .expiresAt(expiresAt)
                .attemptCount(attemptCount)
                .build();
    }

    // --- requesting a code ---

    @Test
    void unknownEmailGeneratesAndSendsNothing() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        authService.requestPasswordReset(new ForgotPasswordRequest("nobody@example.com"));

        verify(otpCodeRepository, never()).save(any());
        verify(otpDeliveryService, never()).send(any(), any(), any());
    }

    @Test
    void issuesAHashedCodeAndSendsTheRawOne() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user(userId)));
        when(otpCodeRepository.countByUserIdAndPurposeAndCreatedAtAfter(eq(userId), eq(OtpPurpose.PASSWORD_RESET), any()))
                .thenReturn(0L);
        when(otpCodeRepository.findByUserIdAndPurposeAndConsumedAtIsNull(userId, OtpPurpose.PASSWORD_RESET))
                .thenReturn(List.of());

        authService.requestPasswordReset(new ForgotPasswordRequest("ada@example.com"));

        ArgumentCaptor<OtpCode> saved = ArgumentCaptor.forClass(OtpCode.class);
        verify(otpCodeRepository).save(saved.capture());
        ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
        verify(otpDeliveryService).send(eq("ada@example.com"), sentCode.capture(), eq(OtpChannel.EMAIL));

        OtpCode persisted = saved.getValue();
        assertThat(sentCode.getValue()).matches("\\d{6}");
        assertThat(persisted.getCodeHash())
                .as("only the hash is persisted, never the code itself")
                .isNotEqualTo(sentCode.getValue())
                .isEqualTo(OtpCodes.hash(sentCode.getValue()));
        assertThat(persisted.getAttemptCount()).isZero();
        assertThat(persisted.getConsumedAt()).isNull();
        assertThat(persisted.getPurpose()).isEqualTo(OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void requestingAgainSupersedesTheOutstandingCode() {
        UUID userId = UUID.randomUUID();
        OtpCode outstanding = code(userId, "111111", Instant.now().plus(CODE_TTL), 0);
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user(userId)));
        when(otpCodeRepository.countByUserIdAndPurposeAndCreatedAtAfter(eq(userId), any(), any())).thenReturn(1L);
        when(otpCodeRepository.findByUserIdAndPurposeAndConsumedAtIsNull(userId, OtpPurpose.PASSWORD_RESET))
                .thenReturn(List.of(outstanding));

        authService.requestPasswordReset(new ForgotPasswordRequest("ada@example.com"));

        assertThat(outstanding.getConsumedAt())
                .as("the previous code must stop working — never two valid codes at once")
                .isNotNull();
        verify(otpCodeRepository).saveAll(List.of(outstanding));
        verify(otpCodeRepository).save(any());
    }

    @Test
    void anAccountOverItsRateLimitGeneratesAndSendsNothing() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user(userId)));
        when(otpCodeRepository.countByUserIdAndPurposeAndCreatedAtAfter(eq(userId), any(), any())).thenReturn(3L);

        authService.requestPasswordReset(new ForgotPasswordRequest("ada@example.com"));

        verify(otpCodeRepository, never()).save(any());
        verify(otpDeliveryService, never()).send(any(), any(), any());
    }

    // --- completing the reset ---

    @Test
    void resetsThePasswordRevokesRefreshTokensAndAudits() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        OtpCode stored = code(userId, "123456", Instant.now().plus(CODE_TTL), 0);
        RefreshToken active = RefreshToken.builder().id(UUID.randomUUID()).userId(userId)
                .tokenHash("h").expiresAt(Instant.now().plus(Duration.ofDays(1))).build();

        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(otpCodeRepository.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                userId, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(stored));
        when(passwordEncoder.encode("a brand new password")).thenReturn("new-hash");
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(active));

        authService.resetPassword(new ResetPasswordRequest("ada@example.com", "123456", "a brand new password"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(stored.getConsumedAt()).as("a used code can never be reused").isNotNull();
        assertThat(active.getRevokedAt()).isNotNull();
        verify(auditApi).record(any());
    }

    @Test
    void anExpiredCodeIsRejectedAndThePasswordIsUntouched() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        OtpCode expired = code(userId, "123456", Instant.now().minusSeconds(1), 0);

        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(otpCodeRepository.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                userId, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("ada@example.com", "123456", "irrelevant")))
                .isInstanceOf(AuthException.InvalidOrExpiredResetCode.class);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
        verify(auditApi, never()).record(any());
    }

    /**
     * The boundary itself: a code expiring in the future is still usable, and
     * the check is on {@code expiresAt} being strictly in the past, so a code
     * is valid right up to its expiry rather than a moment short of it.
     */
    @Test
    void aCodeIsStillValidJustBeforeItsExpiry() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        OtpCode almostExpired = code(userId, "123456", Instant.now().plusSeconds(2), 0);

        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(otpCodeRepository.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                userId, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(almostExpired));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());

        authService.resetPassword(new ResetPasswordRequest("ada@example.com", "123456", "a brand new password"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void aWrongCodeIncrementsTheAttemptCounter() {
        UUID userId = UUID.randomUUID();
        OtpCode stored = code(userId, "123456", Instant.now().plus(CODE_TTL), 2);

        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user(userId)));
        when(otpCodeRepository.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                userId, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("ada@example.com", "000000", "irrelevant")))
                .isInstanceOf(AuthException.InvalidOrExpiredResetCode.class);

        assertThat(stored.getAttemptCount()).isEqualTo(3);
        verify(otpCodeRepository).save(stored);
    }

    @Test
    void anExhaustedCodeIsRejectedEvenWhenTheSubmittedCodeIsCorrect() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        OtpCode exhausted = code(userId, "123456", Instant.now().plus(CODE_TTL), MAX_ATTEMPTS);

        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(otpCodeRepository.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                userId, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(exhausted));

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("ada@example.com", "123456", "irrelevant")))
                .isInstanceOf(AuthException.InvalidOrExpiredResetCode.class);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
    }

    @Test
    void anUnknownEmailFailsTheSameWayAsABadCode() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("nobody@example.com", "123456", "irrelevant")))
                .isInstanceOf(AuthException.InvalidOrExpiredResetCode.class);
    }

    @Test
    void noOutstandingCodeFailsTheSameWay() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user(userId)));
        when(otpCodeRepository.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                userId, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("ada@example.com", "123456", "irrelevant")))
                .isInstanceOf(AuthException.InvalidOrExpiredResetCode.class);
    }
}
