package com.techcomfort.landvaultbackend.identity.internal.security;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RFC 6238 TOTP, delegated to {@code dev.samstevens.totp} rather than
 * hand-rolled — see AGENTS.md. The parameters below are the defaults every
 * authenticator app (Google Authenticator, Authy, 1Password, Microsoft
 * Authenticator) assumes; changing one silently breaks pairing for apps that
 * ignore the {@code otpauth://} URI's parameters.
 * <p>
 * Never logs a secret or a code, at any level.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(TwoFaProperties.class)
public class TotpService {

    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final HashingAlgorithm ALGORITHM = HashingAlgorithm.SHA1;
    private static final String ISSUER = "LandVault";

    private final TwoFaProperties properties;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(ALGORITHM, DIGITS);

    private DefaultCodeVerifier verifier;

    @PostConstruct
    void init() {
        this.verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        this.verifier.setTimePeriod(PERIOD_SECONDS);
        // Clocks drift; ±1 window is ~90 seconds of tolerance. Each extra
        // window multiplies the codes valid at any instant, so this is a
        // security parameter, not a comfort setting.
        this.verifier.setAllowedTimePeriodDiscrepancy(properties.allowedDriftWindows());
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    /** The {@code otpauth://} URI the client renders as a QR code. */
    public String buildOtpAuthUri(String secret, String accountName) {
        return new QrData.Builder()
                .label(accountName)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(ALGORITHM)
                .digits(DIGITS)
                .period(PERIOD_SECONDS)
                .build()
                .getUri();
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null || code.isBlank()) {
            return false;
        }
        return verifier.isValidCode(secret, code);
    }
}
