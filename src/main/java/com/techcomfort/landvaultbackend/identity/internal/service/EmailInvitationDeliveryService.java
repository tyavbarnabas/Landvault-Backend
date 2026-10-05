package com.techcomfort.landvaultbackend.identity.internal.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Sends the invitation by SMTP — MailDev locally, a real provider elsewhere,
 * by configuration only (see {@code EmailOtpDeliveryService} for why this is
 * plain {@link JavaMailSender}). Fails startup without a mail sender, rather
 * than accept invitations it cannot deliver. Logs the fact, never the link.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "landvault.invitations.delivery", havingValue = "email", matchIfMissing = true)
public class EmailInvitationDeliveryService implements InvitationDeliveryService {

    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'WAT'")
            .withZone(ZoneId.of("Africa/Lagos"));

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final InvitationProperties properties;

    private JavaMailSender mailSender;

    @PostConstruct
    void init() {
        this.mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("No JavaMailSender is available, so staff invitations could not be "
                    + "sent. Set spring.mail.* for this environment (locally: run with the dev profile as a real "
                    + "environment variable — see AGENTS.md). Refusing to start rather than create invitations "
                    + "nobody can receive.");
        }
    }

    @Override
    public void send(InvitationEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.fromAddress());
        message.setTo(email.to());
        message.setSubject("You've been invited to join " + email.companyName() + " on LandVault");
        message.setText("""
                Hello %s,

                %s has invited you to LandVault as %s%s.

                Set your password and sign in here:
                %s

                This link works once and expires on %s. If you weren't expecting it, ignore this email — \
                nothing happens unless the link is used.
                """.formatted(email.firstName(), email.companyName(), email.roleName(),
                email.branchName() == null ? "" : ", " + email.branchName(), email.link(),
                EXPIRY.format(email.expiresAt())));
        mailSender.send(message);
        log.info("Staff invitation delivered to an invited address for {}", email.companyName());
    }

    @Override
    public void sendApprovalRequest(ApprovalRequestEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.fromAddress());
        message.setTo(email.to());
        message.setSubject(email.requesterName() + " asked to invite someone to " + email.branchName());
        message.setText("""
                Hello %s,

                %s (%s) has asked to invite %s <%s> as %s.

                Nothing is sent to them until you approve. Review the request here:
                %s

                If nobody approves it, the request lapses on %s.
                """.formatted(email.approverFirstName(), email.requesterName(), email.branchName(),
                email.inviteeName(), email.inviteeEmail(), email.roleName(), email.reviewUrl(),
                EXPIRY.format(email.expiresAt())));
        mailSender.send(message);
        log.info("Invitation approval request delivered to an approver");
    }
}
