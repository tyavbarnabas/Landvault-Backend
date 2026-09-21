package com.techcomfort.landvaultbackend.identity.internal.security;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift tolerance is a security parameter, so it gets pinned rather than
 * assumed: ±1 window is accepted and two windows out is not. Codes are
 * generated for <em>past</em> windows on purpose — a window boundary crossing
 * mid-test only moves them further away, never closer, so the assertions
 * can't flake.
 */
class TotpServiceTest {

    private static final int PERIOD_SECONDS = 30;

    private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final SystemTimeProvider timeProvider = new SystemTimeProvider();

    private TotpService totpService;
    private String secret;

    @BeforeEach
    void setUp() {
        totpService = new TotpService(properties(1));
        totpService.init();
        secret = totpService.generateSecret();
    }

    private static TwoFaProperties properties(int driftWindows) {
        return new TwoFaProperties(
                "bGFuZHZhdWx0LXRlc3Qtb25seS1rZXktMzJieXRlcyE=",
                driftWindows, 5, Duration.ofMinutes(15), Duration.ofMinutes(5), 10);
    }

    private String codeForWindowOffset(int windowOffset) throws Exception {
        long currentBucket = Math.floorDiv(timeProvider.getTime(), PERIOD_SECONDS);
        return codeGenerator.generate(secret, currentBucket + windowOffset);
    }

    @Test
    void acceptsACodeFromTheCurrentWindow() throws Exception {
        assertThat(totpService.verify(secret, codeForWindowOffset(0))).isTrue();
    }

    @Test
    void acceptsACodeOneWindowBehind() throws Exception {
        assertThat(totpService.verify(secret, codeForWindowOffset(-1))).isTrue();
    }

    @Test
    void acceptsACodeOneWindowAhead() throws Exception {
        assertThat(totpService.verify(secret, codeForWindowOffset(1))).isTrue();
    }

    @Test
    void rejectsACodeTwoWindowsBehind() throws Exception {
        assertThat(totpService.verify(secret, codeForWindowOffset(-2)))
                .as("each extra accepted window is extra brute-force surface")
                .isFalse();
    }

    @Test
    void rejectsACodeForADifferentSecret() throws Exception {
        String otherSecret = totpService.generateSecret();
        assertThat(totpService.verify(otherSecret, codeForWindowOffset(0))).isFalse();
    }

    @Test
    void rejectsNullAndBlankCodesWithoutThrowing() {
        assertThat(totpService.verify(secret, null)).isFalse();
        assertThat(totpService.verify(secret, "   ")).isFalse();
        assertThat(totpService.verify(null, "123456")).isFalse();
    }

    @Test
    void buildsAnOtpAuthUriCarryingTheSecretAndIssuer() {
        String uri = totpService.buildOtpAuthUri(secret, "ada@example.com");

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=" + secret);
        assertThat(uri).contains("issuer=LandVault");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
    }

    @Test
    void generatesADifferentSecretEachTime() {
        assertThat(totpService.generateSecret()).isNotEqualTo(totpService.generateSecret());
    }
}
