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

    /** Phase 5a. Factual: what was paid, what comes back, what the developer keeps, and what happens next. */
    @Override
    public void planWithdrawn(WithdrawalAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        String figures = """
                Paid on the plan:            NGN %s
                Returned to the buyer:       NGN %s
                Kept by the developer:       NGN %s
                """.formatted(alert.paid(), alert.refund(), alert.developerKeeps());
        if (alert.toDeveloper()) {
            message.setSubject("A buyer withdrew from their plan: " + alert.purchaseReference());
            message.setText("""
                    Hello,

                    The buyer on purchase %s (%s) has withdrawn from their instalment plan, on the refund terms \
                    your estate declared. The plot is back on sale.

                    %s
                    HomeTrue returns the buyer's part from the money it holds in escrow. Your shares already \
                    verified are paid out as usual.%s%s

                    See it under Finance > Plans in your HomeTrue portal.
                    """.formatted(alert.purchaseReference(), alert.planName(), figures,
                    alert.awaitingFinance() == null ? "" : "\n\nOne payment is still waiting for your finance team to "
                            + "verify it. Your share of it, NGN " + alert.awaitingFinance() + ", is held for you and "
                            + "paid out once it is verified.",
                    alert.reason() == null ? "" : "\n\nThe buyer's reason: " + alert.reason()));
        } else {
            message.setSubject("You have withdrawn from your plan: " + alert.purchaseReference());
            message.setText("""
                    Hello,

                    You have withdrawn from your instalment plan (%s) for purchase %s, on the refund terms set \
                    out in your offer letter. The plot is no longer held for you.

                    %s
                    HomeTrue holds your part and returns it to you through the payment provider, payment by \
                    payment. We'll email you if we need a bank account from you.

                    See your purchase and the refund:

                        %s
                    """.formatted(alert.planName(), alert.purchaseReference(), figures, alert.purchaseUrl()));
        }
        mailSender.send(message);
        log.info("Plan withdrawal delivered to the {}", alert.toDeveloper() ? "developer" : "buyer");
    }

    @Override
    public void planRevocation(RevocationAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        String plot = alert.plotLabel() + " (purchase " + alert.purchaseReference() + ", " + alert.planName() + ")";
        switch (alert.stage()) {
            case NOTICE -> {
                message.setSubject("Notice of revocation: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        Your instalment plan for %s has an instalment still unpaid %d months after its due \
                        date. Under the terms in your offer letter, the developer has given you notice that \
                        they intend to revoke your plan.

                        Pay what is overdue by the end of %s and the notice stops. Paying today would cost:

                            NGN %s

                        If it is not paid by then, the developer may revoke the plan. The plot would no longer \
                        be held for you, and HomeTrue would return to you exactly what withdrawing returns:

                            Returned to you:         NGN %s
                            Kept by the developer:   NGN %s

                        You may also withdraw yourself at any time, on the same terms.%s

                        See your plan and pay:

                            %s
                        """.formatted(plot, alert.afterMonths(), alert.deadline(), alert.totalDue(), alert.refund(),
                        alert.developerKeeps(), alert.note() == null ? "" : "\n\nThe developer's reason: " + alert.note(),
                        alert.purchaseUrl()));
            }
            case CANCELLED -> {
                message.setSubject("Notice of revocation withdrawn: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The developer has withdrawn the notice of revocation on your instalment plan for %s. \
                        Your plan carries on as before; any instalment still overdue remains due.%s

                            %s
                        """.formatted(plot, alert.note() == null ? "" : "\n\nTheir note: " + alert.note(),
                        alert.purchaseUrl()));
            }
            case CLEARED -> {
                message.setSubject("You're back on track: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        Your payment has been verified, and nothing on your instalment plan for %s is overdue \
                        long enough to revoke it any more. The notice of revocation has ended and your plan \
                        carries on.

                            %s
                        """.formatted(plot, alert.purchaseUrl()));
            }
            case REVOKED -> {
                message.setSubject("Your plan has been revoked: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The notice period on your instalment plan for %s has ended with the overdue amount \
                        unpaid, and the developer has revoked the plan. The plot is no longer held for you.

                        HomeTrue returns to you exactly what withdrawing would have returned, from the money it \
                        holds:

                            Returned to you:         NGN %s
                            Kept by the developer:   NGN %s

                        It is paid back through the payment provider, payment by payment. We'll email you if we \
                        need a bank account from you.

                            %s
                        """.formatted(plot, alert.refund(), alert.developerKeeps(), alert.purchaseUrl()));
            }
        }
        mailSender.send(message);
        log.info("Plan revocation {} delivered to the buyer", alert.stage());
    }

    @Override
    public void penaltyWaived(PenaltyWaivedAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("A late-payment penalty was waived: " + alert.purchaseReference());
        message.setText("""
                Hello,

                The developer has waived NGN %s of the late-payment penalty on instalment %d of your instalment \
                plan for %s (purchase %s, %s).

                Their reason: %s

                Penalty still owed on that instalment today: NGN %s. The instalment itself is still due; if it \
                stays unpaid long enough to reach a higher penalty step, the difference applies.

                    %s
                """.formatted(alert.waived(), alert.instalmentSequence(), alert.plotLabel(), alert.purchaseReference(),
                alert.planName(), alert.reason(), alert.stillOwed(), alert.purchaseUrl()));
        mailSender.send(message);
        log.info("Penalty waiver delivered to the buyer");
    }

    @Override
    public void planRestructure(RestructureAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        String plot = alert.plotLabel() == null
                ? "purchase " + alert.purchaseReference() + " (" + alert.planName() + ")"
                : alert.plotLabel() + " (purchase " + alert.purchaseReference() + ", " + alert.planName() + ")";
        String link = alert.purchaseUrl() == null ? "" : "\n\n    " + alert.purchaseUrl();
        String note = alert.note() == null ? "" : alert.note();
        switch (alert.stage()) {
            case REQUESTED -> {
                message.setSubject("A buyer asked to restructure their plan: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The buyer on %s has asked for their remaining instalments to be spread differently.

                        Their reason: %s

                        Your finance team can propose new terms or decline, under Finance > Restructures in your \
                        HomeTrue portal. The total owed can't change; only how it is spread.
                        """.formatted(plot, note));
            }
            case PROPOSED -> {
                message.setSubject("New terms proposed for your plan: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The developer has proposed new terms for the rest of your instalment plan for %s:

                        %s

                        Nothing changes until you accept. Read restructure letter %s in your documents and accept \
                        it by the end of %s, or it lapses and your current schedule carries on.%s
                        """.formatted(plot, alert.terms(), alert.letterNumber(), alert.expiresOn(), link));
            }
            case REJECTED -> {
                message.setSubject("Your restructure request was declined: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The developer has declined your request to restructure your instalment plan for %s.

                        Their reason: %s

                        Your current schedule carries on. You may ask again.%s
                        """.formatted(plot, note, link));
            }
            case WITHDRAWN -> {
                message.setSubject("A restructure proposal was withdrawn: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The developer has withdrawn the new terms they proposed for your instalment plan for %s \
                        (letter %s). It can no longer be accepted; your current schedule carries on. Your \
                        request is still open, and they may propose different terms.%s%s
                        """.formatted(plot, alert.letterNumber(), note.isEmpty() ? "" : "\n\nTheir note: " + note,
                        link));
            }
            case LAPSING -> {
                message.setSubject("Your restructure proposal ends soon: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The new terms proposed for your instalment plan for %s (letter %s) can be accepted until \
                        the end of %s. After that they lapse and your current schedule carries on.%s
                        """.formatted(plot, alert.letterNumber(), alert.expiresOn(), link));
            }
            case LAPSED -> {
                message.setSubject("Your restructure proposal has lapsed: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The new terms proposed for your instalment plan for %s (letter %s) were not accepted in \
                        time%s, so they have lapsed. Your current schedule carries on, and you may ask again.%s
                        """.formatted(plot, alert.letterNumber(), note.isEmpty() ? "" : " — " + note, link));
            }
            case ACCEPTED -> {
                message.setSubject((alert.toDeveloper() ? "A buyer accepted your restructure: "
                        : "Your plan has been restructured: ") + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The restructure of the instalment plan for %s (letter %s) has been accepted and is now in \
                        force:

                        %s

                        Instalments already paid stay paid. The earlier schedule is kept as history.%s
                        """.formatted(plot, alert.letterNumber(), alert.terms(), link));
            }
            case DECLINED -> {
                message.setSubject("A buyer declined your restructure proposal: " + alert.purchaseReference());
                message.setText("""
                        Hello,

                        The buyer on %s has declined the new terms you proposed (letter %s). Their current \
                        schedule carries on.
                        """.formatted(plot, alert.letterNumber()));
            }
        }
        mailSender.send(message);
        log.info("Plan restructure {} delivered to the {}", alert.stage(), alert.toDeveloper() ? "developer" : "buyer");
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
