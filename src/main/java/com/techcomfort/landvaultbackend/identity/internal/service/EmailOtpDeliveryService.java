package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.internal.enums.OtpChannel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Sends the code by SMTP. The default implementation, and deliberately
 * <strong>environment-agnostic</strong>: it never knows whether it's talking
 * to MailDev, Brevo, SES or a self-hosted server, because that is entirely a
 * matter of {@code spring.mail.*} per profile. Switching providers is a
 * host/port/credentials change, not a change to this class — which is the
 * whole reason delivery goes through plain {@link JavaMailSender} rather than
 * a vendor SDK. See AGENTS.md.
 * <p>
 * Plain text on purpose: an HTML template system is out of scope, and a
 * six-digit code needs no layout.
 * <p>
 * {@link JavaMailSender} arrives through an {@link ObjectProvider} rather
 * than as a direct constructor parameter. It is still <em>required</em> — see
 * {@link #init()}, which fails startup without it — but Boot only creates
 * that bean when {@code spring.mail.host} is set, so a direct parameter
 * produced a bare "Parameter 0 of constructor … required a bean of type
 * 'JavaMailSender'". That message names the wrong culprit: the class is fine,
 * the profile carrying the SMTP config wasn't active. Resolving it here lets
 * the failure say so. Same fail-fast-with-an-explanation pattern as
 * {@code JwtService}'s missing-{@code JWT_SECRET} check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "landvault.otp.delivery", havingValue = "email", matchIfMissing = true)
public class EmailOtpDeliveryService implements OtpDeliveryService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final OtpProperties otpProperties;

    private JavaMailSender mailSender;

    @PostConstruct
    void init() {
        this.mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException(
                    "No JavaMailSender is available, so password reset codes could not be sent. "
                            + "Boot only creates one when spring.mail.host is set, and the base application.yml "
                            + "deliberately defines no default host.\n"
                            + "  - Locally: set SPRING_PROFILES_ACTIVE=dev as a REAL environment variable (on the "
                            + "IntelliJ run configuration, or in the shell) so application-dev.yml points at MailDev. "
                            + "Putting it in .env does NOT work — profiles are resolved before dotenv's property "
                            + "source is read. See AGENTS.md.\n"
                            + "  - Otherwise: configure spring.mail.* for this environment.\n"
                            + "Refusing to start rather than accept password reset requests it cannot deliver.");
        }
    }

    @Override
    public void send(String destination, String code, OtpChannel channel) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(otpProperties.fromAddress());
        message.setTo(destination);
        message.setSubject("Your LandVault password reset code");
        message.setText(body(code));

        mailSender.send(message);
        // The fact, never the code — same rule as SuperAdminBootstrap's
        // password. Only LoggingOtpDeliveryService may log a code, and it is
        // not for real environments.
        log.info("Password reset code delivered by {} to a registered address", channel);
    }

    private String body(String code) {
        long minutes = Duration.ofSeconds(otpProperties.codeTtl().toSeconds()).toMinutes();
        return """
                Your LandVault password reset code is %s.

                It expires in %d minutes and can be used once.

                If you didn't request a password reset, ignore this email — your password has not changed.
                """.formatted(code, minutes);
    }
}
