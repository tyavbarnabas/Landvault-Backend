package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.RecoveryCodesResponse;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaSetupResponse;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaVerifyRequest;
import com.techcomfort.landvaultbackend.identity.internal.domain.RecoveryCode;
import com.techcomfort.landvaultbackend.identity.internal.domain.TwoFaChallenge;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import com.techcomfort.landvaultbackend.identity.internal.repository.RecoveryCodeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.TwoFaChallengeRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.security.TotpService;
import com.techcomfort.landvaultbackend.identity.internal.security.TwoFaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Setup, confirmation, second-step verification, disable and recovery-code
 * regeneration. See AGENTS.md for why TOTP rather than reusing
 * {@code otp_codes}, and why setup and confirmation are separate states.
 * <p>
 * Never logs a secret, a TOTP code or a recovery code, at any level.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoFactorService {

    private static final Set<String> PLATFORM_STAFF_ROLE_CODES =
            Set.of("super_admin", "platform_moderator", "compliance_officer");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RECOVERY_CODE_BYTES = 8;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final TwoFaChallengeRepository twoFaChallengeRepository;
    private final TotpService totpService;
    private final TwoFaProperties twoFaProperties;
    private final AuthService authService;
    private final AuditApi auditApi;

    /**
     * Issues a secret and the {@code otpauth://} URI. Deliberately does
     * <strong>not</strong> enable 2FA — see {@link #confirm}. Calling this
     * again before confirming replaces the unconfirmed secret, so a user who
     * mis-scanned can simply start over.
     */
    @Transactional
    public TwoFaSetupResponse setup(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);

        String secret = totpService.generateSecret();
        user.setTwoFaSecret(secret);
        user.setTwoFaEnabled(false);
        user.setTwoFaConfirmedAt(null);
        userRepository.save(user);

        log.info("Two-factor setup started for user {}", userId);
        return new TwoFaSetupResponse(secret, totpService.buildOtpAuthUri(secret, user.getEmail()));
    }

    /**
     * Verifies the first code and only then enables 2FA, issuing recovery
     * codes in the same transaction.
     * <p>
     * <strong>This step is not optional.</strong> Enabling 2FA at setup time,
     * before the user's app demonstrably holds the secret, permanently locks
     * them out if the pairing silently failed — recovery codes are issued
     * here, so there would be nothing to recover with. It is the single worst
     * outcome this feature can produce, and the reason
     * {@code two_fa_confirmed_at} exists as its own column.
     */
    @Transactional(noRollbackFor = {AuthException.InvalidTwoFactorCode.class, AuthException.TwoFactorLockedOut.class})
    public RecoveryCodesResponse confirm(UUID userId, String code) {
        User user = userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
        if (user.getTwoFaSecret() == null) {
            throw new AuthException.TwoFactorSetupRequired();
        }
        requireNotLockedOut(user);

        if (!totpService.verify(user.getTwoFaSecret(), code)) {
            registerFailedAttempt(user);
            throw new AuthException.InvalidTwoFactorCode();
        }

        user.setTwoFaEnabled(true);
        user.setTwoFaConfirmedAt(Instant.now());
        clearFailedAttempts(user);
        userRepository.save(user);

        List<String> codes = replaceRecoveryCodes(user.getId());

        auditApi.record(AuditEntryRequest.of(
                user.getId(), "auth.two_factor_enabled", "user", user.getId(), user.getTenantId(),
                "Two-factor authentication confirmed and enabled."));
        log.info("Two-factor enabled for user {}", userId);

        return new RecoveryCodesResponse(codes);
    }

    /**
     * The second step of login: exchanges a challenge plus a TOTP or recovery
     * code for the real tokens. The challenge is consumed here, so it cannot
     * be replayed even inside its short lifetime.
     */
    @Transactional(noRollbackFor = {AuthException.InvalidTwoFactorCode.class, AuthException.TwoFactorLockedOut.class})
    public AuthResponse verify(TwoFaVerifyRequest request) {
        TwoFaChallenge challenge = twoFaChallengeRepository.findByTokenHash(OtpCodes.hash(request.challengeToken()))
                .orElseThrow(AuthException.InvalidOrExpiredChallenge::new);
        if (challenge.getConsumedAt() != null || challenge.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthException.InvalidOrExpiredChallenge();
        }

        User user = userRepository.findById(challenge.getUserId())
                .orElseThrow(AuthException.InvalidOrExpiredChallenge::new);
        requireNotLockedOut(user);

        if (!consumeSecondFactor(user, request.code())) {
            registerFailedAttempt(user);
            throw new AuthException.InvalidTwoFactorCode();
        }

        // Single use — set before issuing anything, so a concurrent replay of
        // the same challenge finds it already spent.
        challenge.setConsumedAt(Instant.now());
        twoFaChallengeRepository.save(challenge);

        clearFailedAttempts(user);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        log.info("Second factor verified for user {}", user.getId());
        return authService.issueAuthResponse(user, userRoleRepository.findByUserId(user.getId()));
    }

    /**
     * Turning 2FA off requires a current TOTP code or a recovery code — a
     * session alone is deliberately not enough, since a hijacked session
     * could otherwise strip exactly the protection 2FA exists to provide.
     */
    @Transactional(noRollbackFor = {AuthException.InvalidTwoFactorCode.class, AuthException.TwoFactorLockedOut.class})
    public void disable(UUID userId, String code) {
        User user = userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
        requireTwoFactorEnabled(user);

        // Checked before the code, so platform staff get told why rather than
        // being invited to keep guessing at a door that never opens.
        if (isPlatformStaff(user)) {
            throw new AuthException.TwoFactorMandatory();
        }
        requireNotLockedOut(user);

        if (!consumeSecondFactor(user, code)) {
            registerFailedAttempt(user);
            throw new AuthException.InvalidTwoFactorCode();
        }

        user.setTwoFaEnabled(false);
        user.setTwoFaConfirmedAt(null);
        user.setTwoFaSecret(null);
        clearFailedAttempts(user);
        userRepository.save(user);

        recoveryCodeRepository.deleteAll(recoveryCodeRepository.findByUserIdAndConsumedAtIsNull(userId));

        auditApi.record(AuditEntryRequest.of(
                user.getId(), "auth.two_factor_disabled", "user", user.getId(), user.getTenantId(),
                "Two-factor authentication disabled."));
        log.info("Two-factor disabled for user {}", userId);
    }

    /** Invalidates every existing code and issues a fresh set. Requires a valid TOTP code. */
    @Transactional(noRollbackFor = {AuthException.InvalidTwoFactorCode.class, AuthException.TwoFactorLockedOut.class})
    public RecoveryCodesResponse regenerateRecoveryCodes(UUID userId, String code) {
        User user = userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
        requireTwoFactorEnabled(user);
        requireNotLockedOut(user);

        // TOTP only, deliberately: allowing a recovery code here would let
        // someone holding one stale code mint a whole new set.
        if (!totpService.verify(user.getTwoFaSecret(), code)) {
            registerFailedAttempt(user);
            throw new AuthException.InvalidTwoFactorCode();
        }
        clearFailedAttempts(user);
        userRepository.save(user);

        List<String> codes = replaceRecoveryCodes(userId);
        log.info("Recovery codes regenerated for user {}", userId);
        return new RecoveryCodesResponse(codes);
    }

    // --- second factor: a TOTP code, or a single-use recovery code ---

    private boolean consumeSecondFactor(User user, String code) {
        if (totpService.verify(user.getTwoFaSecret(), code)) {
            return true;
        }
        Optional<RecoveryCode> match = recoveryCodeRepository.findByUserIdAndConsumedAtIsNull(user.getId()).stream()
                .filter(candidate -> OtpCodes.matches(normalise(code), candidate.getCodeHash()))
                .findFirst();
        if (match.isEmpty()) {
            return false;
        }

        RecoveryCode used = match.get();
        used.setConsumedAt(Instant.now());
        recoveryCodeRepository.save(used);

        // A recovery code means the user lost device access — worth a record
        // of its own, separate from an ordinary sign-in.
        auditApi.record(AuditEntryRequest.of(
                user.getId(), "auth.two_factor_recovery_code_used", "user", user.getId(), user.getTenantId(),
                "A recovery code was used in place of a TOTP code."));
        log.info("Recovery code used for user {}; {} remaining", user.getId(),
                recoveryCodeRepository.countByUserIdAndConsumedAtIsNull(user.getId()));
        return true;
    }

    private List<String> replaceRecoveryCodes(UUID userId) {
        recoveryCodeRepository.deleteAll(recoveryCodeRepository.findByUserIdAndConsumedAtIsNull(userId));

        List<String> plaintext = IntStream.range(0, twoFaProperties.recoveryCodeCount())
                .mapToObj(i -> generateRecoveryCode())
                .toList();

        recoveryCodeRepository.saveAll(plaintext.stream()
                .map(code -> RecoveryCode.builder()
                        .userId(userId)
                        .codeHash(OtpCodes.hash(normalise(code)))
                        .build())
                .toList());

        // Returned to the caller once and never stored in plaintext anywhere.
        return plaintext;
    }

    // 64 bits of randomness, grouped for legibility — deliberately not six
    // digits: unlike a TOTP code this has no expiry, so it has to survive
    // being guessable over a long period.
    private static String generateRecoveryCode() {
        byte[] bytes = new byte[RECOVERY_CODE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12);
    }

    // Users retype recovery codes by hand; case and the grouping dashes
    // shouldn't decide whether they get back into their account.
    private static String normalise(String code) {
        return code == null ? "" : code.trim().toLowerCase().replace("-", "");
    }

    // --- throttling ---

    private void requireNotLockedOut(User user) {
        if (user.getTwoFaLockedUntil() != null && user.getTwoFaLockedUntil().isAfter(Instant.now())) {
            throw new AuthException.TwoFactorLockedOut();
        }
    }

    /**
     * Increments, and locks out once the limit is reached. The caller throws
     * straight after calling this, which is why every method here carries
     * {@code noRollbackFor} — Spring's default rollback would undo the
     * increment along with the failed request and leave the limiter
     * permanently at zero. Same trap as {@code resetPassword} and
     * {@code refresh}; see AGENTS.md.
     */
    private void registerFailedAttempt(User user) {
        int attempts = (user.getTwoFaFailedAttempts() == null ? 0 : user.getTwoFaFailedAttempts()) + 1;
        if (attempts >= twoFaProperties.maxFailedAttempts()) {
            user.setTwoFaFailedAttempts(0);
            user.setTwoFaLockedUntil(Instant.now().plus(twoFaProperties.lockoutDuration()));
            log.info("Two-factor lockout applied for user {}", user.getId());
        } else {
            user.setTwoFaFailedAttempts(attempts);
        }
        userRepository.save(user);
    }

    private void clearFailedAttempts(User user) {
        user.setTwoFaFailedAttempts(0);
        user.setTwoFaLockedUntil(null);
    }

    // --- shared guards ---

    private void requireTwoFactorEnabled(User user) {
        if (!Boolean.TRUE.equals(user.getTwoFaEnabled()) || user.getTwoFaConfirmedAt() == null) {
            throw new AuthException.TwoFactorNotEnabled();
        }
    }

    private boolean isPlatformStaff(User user) {
        List<UUID> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
                .map(assignment -> assignment.getRoleId())
                .distinct()
                .toList();
        return roleRepository.findAllById(roleIds).stream()
                .anyMatch(role -> PLATFORM_STAFF_ROLE_CODES.contains(role.getCode()));
    }
}
