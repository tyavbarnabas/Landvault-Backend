package com.techcomfort.landvaultbackend.resale.internal.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Sends the resale emails by SMTP — same arrangement as the other mailers. */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.resale.delivery", havingValue = "email", matchIfMissing = true)
public class EmailResaleMailer implements ResaleMailer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ResaleProperties properties;

    private JavaMailSender mailSender;

    public EmailResaleMailer(ObjectProvider<JavaMailSender> mailSenderProvider, ResaleProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("No JavaMailSender is available, so resale emails could not be sent. "
                    + "Set spring.mail.* for this environment (locally: the dev profile — see AGENTS.md).");
        }
    }

    @Override
    public void consentRequested(ListingAlert alert, LocalDate answerBy) {
        send(alert.to(), "Consent needed: " + alert.plotLabel() + ", " + alert.estateName(), """
                Hello,

                %s has asked HomeTrue to sell %s in %s (purchase %s), at %s%s.

                The estate's terms say a transfer needs your consent, so the plot is not on the marketplace \
                yet. Please give or refuse consent by %s. If you refuse, give your reason: the seller will \
                read it. With no answer by then, the request lapses and the plot stays off the marketplace.

                Review it here: %s

                The HomeTrue team
                """.formatted(alert.sellerName(), alert.plotLabel(), alert.estateName(),
                alert.transactionReference(), alert.price(), earlyNote(alert), DATE.format(answerBy),
                alert.reviewUrl()));
    }

    @Override
    public void listedNotice(ListingAlert alert) {
        send(alert.to(), "Listed for resale: " + alert.plotLabel() + ", " + alert.estateName(), """
                Hello,

                %s has listed %s in %s (purchase %s) for resale on HomeTrue, at %s%s.

                The estate's terms don't require your consent to a transfer, so it is on the marketplace now. \
                Nothing is needed from you; this is to let you know.

                See your estates' resales here: %s

                The HomeTrue team
                """.formatted(alert.sellerName(), alert.plotLabel(), alert.estateName(),
                alert.transactionReference(), alert.price(), earlyNote(alert), alert.reviewUrl()));
    }

    private static String earlyNote(ListingAlert alert) {
        return alert.earlySale() ? " — your current price, as an early sale (any gain above what the buyer agreed "
                + "goes to you)" : "";
    }

    private void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
        log.info("Resale email delivered: {}", subject.substring(0, subject.indexOf(':')));
    }
}
