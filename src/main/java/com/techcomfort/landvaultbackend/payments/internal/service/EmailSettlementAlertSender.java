package com.techcomfort.landvaultbackend.payments.internal.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Sends the payout-account alert by SMTP — same arrangement as the invitation and OTP emails. */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.payments.alerts.delivery", havingValue = "email", matchIfMissing = true)
public class EmailSettlementAlertSender implements SettlementAlertSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;

    private JavaMailSender mailSender;

    public EmailSettlementAlertSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                      @Value("${landvault.payments.alerts.from-address}") String fromAddress) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
    }

    @PostConstruct
    void init() {
        this.mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("No JavaMailSender is available, so payout-account alerts could not "
                    + "be sent. Set spring.mail.* for this environment (locally: the dev profile — see AGENTS.md).");
        }
    }

    @Override
    public void accountSubmitted(AccountSubmittedAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("A new payout bank account was submitted for " + alert.companyName());
        message.setText("""
                Hello,

                A new bank account for %s's HomeTrue payouts was submitted by %s:

                    %s, account ending %s

                It won't receive any money until HomeTrue approves it. If you didn't expect this change, \
                contact HomeTrue support straight away — before it is approved.
                """.formatted(alert.companyName(), alert.submittedByEmail(), alert.bankName(), alert.accountLast4()));
        mailSender.send(message);
        log.info("Payout-account alert delivered to an Executive Director of {}", alert.companyName());
    }

    @Override
    public void payoutProblem(PayoutProblemAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Payout " + alert.status() + " for " + alert.companyName());
        message.setText("""
                A HomeTrue payout needs attention.

                    Company:   %s
                    Payout:    %s (%s)
                    Amount:    NGN %s
                    Status:    %s

                %s

                Open the payouts screen to review it and, if needed, pay that part again.
                """.formatted(alert.companyName(), alert.payoutReference(), alert.part(), alert.amount(),
                alert.status(), alert.detail()));
        mailSender.send(message);
        log.info("Payout problem alert delivered to a Super Admin for {}", alert.companyName());
    }

    @Override
    public void refundNeedsAccount(RefundNeedsAccountAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Your HomeTrue refund needs your bank account");
        message.setText("""
                Hello,

                We're refunding NGN %s for your payment %s. Because it was paid by bank transfer, the payment \
                provider needs to know which account to send it to.

                Add a bank account in your own name to receive it:

                    %s
                """.formatted(alert.amount(), alert.paymentReference(), alert.purchaseUrl()));
        mailSender.send(message);
        log.info("Refund account request delivered to a buyer");
    }

    @Override
    public void refundProblem(RefundProblemAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Refund " + alert.status() + " for payment " + alert.paymentReference());
        message.setText("""
                A HomeTrue refund needs attention.

                    Payment:   %s
                    Amount:    NGN %s
                    Status:    %s

                %s
                """.formatted(alert.paymentReference(), alert.amount(), alert.status(), alert.detail()));
        mailSender.send(message);
        log.info("Refund problem alert delivered to a Super Admin");
    }

    @Override
    public void latePaymentResolved(LatePaymentAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        if (alert.allocated()) {
            message.setSubject("Good news: " + alert.plotLabel() + " at " + alert.estateName() + " is yours");
            message.setText("""
                    Hello,

                    Your payment of NGN %s arrived after your reservation had ended. The plot was still \
                    available, so it has been allocated to you: %s, %s.
                    %s
                    See your purchase:

                        %s
                    """.formatted(alert.amount(), alert.plotLabel(), alert.estateName(),
                    letterParagraph("allocation letter", alert.letterNumber(), alert.letterUrl()),
                    alert.purchaseUrl()));
        } else {
            message.setSubject("Your payment for " + alert.plotLabel() + " is being returned");
            message.setText("""
                    Hello,

                    Your payment of NGN %s arrived after your reservation for %s, %s had ended, and it \
                    couldn't be allocated: %s

                    The full amount is being returned to you. Follow the refund on your purchase:

                        %s
                    """.formatted(alert.amount(), alert.plotLabel(), alert.estateName(), alert.reason(),
                    alert.purchaseUrl()));
        }
        mailSender.send(message);
        log.info("Late-payment outcome delivered to a buyer");
    }

    @Override
    public void purchaseDecided(PurchaseDecisionAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        if (alert.verified() && alert.planName() != null) {
            // Phase 3a: a deposit holds the plot on the plan; it is the buyer's once every instalment is paid.
            message.setSubject("Deposit verified: " + alert.plotLabel() + " at " + alert.estateName()
                    + " is held for you");
            message.setText("""
                    Hello,

                    The developer has verified your deposit of NGN %s on the %s. %s, %s is now held for you on \
                    the plan, and becomes yours when every instalment is paid and verified.
                    %s
                    See your purchase and its schedule:

                        %s
                    """.formatted(alert.amount(), alert.planName(), alert.plotLabel(), alert.estateName(),
                    letterParagraph("provisional allocation letter", alert.letterNumber(), alert.letterUrl()),
                    alert.purchaseUrl()));
        } else if (alert.verified()) {
            message.setSubject("Good news: " + alert.plotLabel() + " at " + alert.estateName() + " is yours");
            message.setText("""
                    Hello,

                    The developer has verified your payment of NGN %s, and the plot is allocated to you: \
                    %s, %s.
                    %s
                    See your purchase:

                        %s
                    """.formatted(alert.amount(), alert.plotLabel(), alert.estateName(),
                    letterParagraph("allocation letter", alert.letterNumber(), alert.letterUrl()), alert.purchaseUrl()));
        } else {
            message.setSubject("Your payment for " + alert.plotLabel() + " is being returned");
            message.setText("""
                    Hello,

                    The developer didn't accept your payment for %s, %s. Their reason: %s

                    The full NGN %s is being returned to you. Follow the refund on your purchase:

                        %s
                    """.formatted(alert.plotLabel(), alert.estateName(), alert.reason(), alert.amount(),
                    alert.purchaseUrl()));
        }
        mailSender.send(message);
        log.info("Purchase decision delivered to a buyer");
    }

    @Override
    public void instalmentVerified(InstalmentAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Instalment " + alert.instalmentsPaid() + " of " + alert.instalmentCount() + " verified: "
                + alert.plotLabel() + " at " + alert.estateName());
        message.setText("""
                Hello,

                The developer has verified your instalment of NGN %s for %s, %s. You have now paid %d of %d \
                instalments.
                %s
                See your purchase and its schedule:

                    %s
                """.formatted(alert.amount(), alert.plotLabel(), alert.estateName(), alert.instalmentsPaid(),
                alert.instalmentCount(), alert.nextDueDate() == null ? ""
                        : "\nYour next instalment, NGN " + alert.nextDueAmount() + ", is due on " + alert.nextDueDate()
                        + ".\n", alert.purchaseUrl()));
        mailSender.send(message);
        log.info("Instalment verification delivered to a buyer");
    }

    /**
     * AR-3. Factual and calm, like the conflict guidance: what is due, by when,
     * and what happens next — never a threat.
     */
    @Override
    public void instalmentReminder(InstalmentReminderAlert alert) {
        String instalment = "Instalment " + alert.sequence() + " of " + alert.instalmentCount();
        String what = instalment + " for " + alert.plotLabel() + ", " + alert.estateName() + ", NGN " + alert.amount();
        String subject;
        String body;
        switch (alert.stage()) {
            case "SEVEN_DAYS_BEFORE", "ONE_DAY_BEFORE" -> {
                subject = instalment + " is due on " + alert.dueDate();
                body = "This is a reminder that " + what.substring(0, 1).toLowerCase(Locale.ROOT) + what.substring(1)
                        + ", is due on " + alert.dueDate() + ".";
            }
            case "OVERDUE" -> {
                subject = instalment + " was due on " + alert.dueDate();
                body = what + ", was due on " + alert.dueDate() + " and we haven't received it yet. If you have "
                        + "already paid, there is nothing to do. Otherwise, paying by " + alert.graceEnds()
                        + " keeps your plan up to date; after that it is in arrears"
                        + (alert.penaltiesDeclared() ? ", and the late-payment penalties set out in your offer letter "
                        + "apply as it becomes later." : ".");
            }
            default -> {
                subject = instalment + " is in arrears";
                body = what + ", was due on " + alert.dueDate() + " and is now in arrears."
                        + (alert.penalty() == null
                        ? " Paying it now, NGN " + alert.totalDue() + ", brings your plan back up to date."
                        : " A late-payment penalty of NGN " + alert.penalty() + " applies, as set out in your offer "
                        + "letter, so paying it now is NGN " + alert.totalDue() + " and brings your plan back up "
                        + "to date.")
                        + " If it stays unpaid, the developer may take the steps set out in your offer letter.";
            }
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject(subject + ": " + alert.plotLabel());
        message.setText("""
                Hello,

                %s

                See your schedule and pay in your HomeTrue account:

                    %s

                Payments are only ever made through HomeTrue.
                """.formatted(body, alert.purchaseUrl()));
        mailSender.send(message);
        log.info("Instalment reminder ({}) delivered to a buyer", alert.stage());
    }

    /** Names the allocation letter and links to it; a blank line when there is none (never expected once allocated). */
    private static String letterParagraph(String kind, String letterNumber, String letterUrl) {
        if (letterUrl == null) {
            return "";
        }
        return """

                Your %s, %s, is ready. Sign in to view and download it:

                    %s
                """.formatted(kind, letterNumber, letterUrl);
    }
}
