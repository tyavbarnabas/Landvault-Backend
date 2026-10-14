package com.techcomfort.landvaultbackend.tenancy.internal.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends onboarding-request emails by SMTP — same arrangement as the invitation and OTP emails. */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.onboarding.delivery", havingValue = "email", matchIfMissing = true)
public class EmailOnboardingRequestMailer implements OnboardingRequestMailer {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final OnboardingProperties properties;

    private JavaMailSender mailSender;

    public EmailOnboardingRequestMailer(ObjectProvider<JavaMailSender> mailSenderProvider,
                                        OnboardingProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("No JavaMailSender is available, so onboarding requests could not be "
                    + "announced. Set spring.mail.* for this environment (locally: the dev profile — see AGENTS.md).");
        }
    }

    @Override
    public void requestReceived(RequestReceivedAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(alert.to());
        message.setReplyTo(alert.contactEmail());
        message.setSubject("New onboarding request: " + alert.companyName());
        message.setText("""
                A developer company has asked to be onboarded to HomeTrue.

                Company
                    Name:            %s
                    RC number:       %s
                    Address:         %s
                    States:          %s
                    Estates (about): %s

                Contact
                    Name:            %s
                    Role:            %s
                    Email:           %s
                    Phone:           %s
                    Prefers:         %s
                    Best time:       %s

                Message
                %s

                Nothing has been created. Contact them, then convert or decline the request in the \
                onboarding requests queue of the admin console. Replying to this email reaches the contact.
                """.formatted(alert.companyName(), orDash(alert.rcNumber()), orDash(alert.companyAddress()),
                orDash(alert.statesOfOperation()),
                alert.estimatedEstateCount() == null ? "-" : alert.estimatedEstateCount().toString(),
                alert.contactName(), orDash(alert.contactRole()), alert.contactEmail(), alert.contactPhone(),
                alert.preferredContactMethod(), orDash(alert.preferredContactTime()), orDash(alert.message())));
        mailSender.send(message);
        log.info("Onboarding request alert delivered to HomeTrue");
    }

    @Override
    public void confirmation(String to) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(to);
        message.setSubject("We received your request to join HomeTrue");
        message.setText("""
                Hello,

                Thank you for your interest in listing your estates on HomeTrue. We have received your \
                request, and our team will contact you %s.

                If you didn't ask to join HomeTrue, you can ignore this email; nothing has been set up \
                in your name.

                The HomeTrue team
                """.formatted(properties.getResponseTime()));
        mailSender.send(message);
        log.info("Onboarding request confirmation delivered");
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
