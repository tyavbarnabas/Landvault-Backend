package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.common.PlanNames;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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
                    Amount:    %s
                    Status:    %s

                %s

                Open the payouts screen to review it and, if needed, pay that part again.
                """.formatted(alert.companyName(), alert.payoutReference(), alert.part(), naira(alert.amount()),
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

                We're refunding %s for your payment %s. Because it was paid by bank transfer, the payment \
                provider needs to know which account to send it to.

                Add a bank account in your own name to receive it:

                    %s
                """.formatted(naira(alert.amount()), alert.paymentReference(), alert.purchaseUrl()));
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
                    Amount:    %s
                    Status:    %s

                %s
                """.formatted(alert.paymentReference(), naira(alert.amount()), alert.status(), alert.detail()));
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

                    Your payment of %s arrived after your reservation had ended. The plot was still \
                    available, so it has been allocated to you: %s, %s.
                    %s
                    See your purchase:

                        %s
                    """.formatted(naira(alert.amount()), alert.plotLabel(), alert.estateName(),
                    letterParagraph("allocation letter", alert.letterNumber(), alert.letterUrl()),
                    alert.purchaseUrl()));
        } else {
            message.setSubject("Your payment for " + alert.plotLabel() + " is being returned");
            message.setText("""
                    Hello,

                    Your payment of %s arrived after your reservation for %s, %s had ended, and it \
                    couldn't be allocated: %s

                    The full amount is being returned to you. Follow the refund on your purchase:

                        %s
                    """.formatted(naira(alert.amount()), alert.plotLabel(), alert.estateName(), alert.reason(),
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
        if (alert.verified() && alert.flexible()) {
            // Flexible contribution, phase 3a: the first payment holds the plot; no schedule follows.
            message.setSubject("First payment verified: " + alert.plotLabel() + " at " + alert.estateName()
                    + " is held for you");
            message.setText("""
                    Hello,

                    The developer has verified your first payment of %s on %s. %s, %s is now held for \
                    you, and becomes yours when the plan total is paid and verified. You can pay any amount, at \
                    any time, until then.
                    %s
                    See your purchase:

                        %s
                    """.formatted(naira(alert.amount()), PlanNames.phrase(alert.planName()), alert.plotLabel(),
                    alert.estateName(),
                    letterParagraph("provisional allocation letter", alert.letterNumber(), alert.letterUrl()),
                    alert.purchaseUrl()));
        } else if (alert.verified() && alert.planName() != null) {
            // Phase 3a: a deposit holds the plot on the plan; it is the buyer's once every instalment is paid.
            message.setSubject("Deposit verified: " + alert.plotLabel() + " at " + alert.estateName()
                    + " is held for you");
            message.setText("""
                    Hello,

                    The developer has verified your deposit of %s on %s. %s, %s is now held for you on \
                    the plan, and becomes yours when every instalment is paid and verified.
                    %s
                    See your purchase and its schedule:

                        %s
                    """.formatted(naira(alert.amount()), PlanNames.phrase(alert.planName()), alert.plotLabel(),
                    alert.estateName(),
                    letterParagraph("provisional allocation letter", alert.letterNumber(), alert.letterUrl()),
                    alert.purchaseUrl()));
        } else if (alert.verified() && alert.paidInFull() != null) {
            // A plan's last payment: the plan is paid in full, not "your payment of" its last part.
            message.setSubject("Good news: " + alert.plotLabel() + " at " + alert.estateName() + " is yours");
            message.setText("""
                    Hello,

                    The developer has verified your payment of %s, and your plan is now paid in full \
                    (%s). The plot is allocated to you: %s, %s.
                    %s
                    See your purchase:

                        %s
                    """.formatted(naira(alert.amount()), naira(alert.paidInFull()), alert.plotLabel(), alert.estateName(),
                    letterParagraph("allocation letter", alert.letterNumber(), alert.letterUrl()), alert.purchaseUrl()));
        } else if (alert.verified()) {
            message.setSubject("Good news: " + alert.plotLabel() + " at " + alert.estateName() + " is yours");
            message.setText("""
                    Hello,

                    The developer has verified your payment of %s, and the plot is allocated to you: \
                    %s, %s.
                    %s
                    See your purchase:

                        %s
                    """.formatted(naira(alert.amount()), alert.plotLabel(), alert.estateName(),
                    letterParagraph("allocation letter", alert.letterNumber(), alert.letterUrl()), alert.purchaseUrl()));
        } else {
            message.setSubject("Your payment for " + alert.plotLabel() + " is being returned");
            message.setText("""
                    Hello,

                    The developer didn't accept your payment for %s, %s. Their reason: %s

                    The full %s is being returned to you. Follow the refund on your purchase:

                        %s
                    """.formatted(alert.plotLabel(), alert.estateName(), alert.reason(), naira(alert.amount()),
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

                The developer has verified your instalment of %s for %s, %s. You have now paid %d of %d \
                instalments.
                %s
                See your purchase and its schedule:

                    %s
                """.formatted(naira(alert.amount()), alert.plotLabel(), alert.estateName(), alert.instalmentsPaid(),
                alert.instalmentCount(), alert.nextDueDate() == null ? ""
                        : "\nYour next instalment, " + naira(alert.nextDueAmount()) + ", is due on " + alert.nextDueDate()
                        + ".\n", alert.purchaseUrl()));
        mailSender.send(message);
        log.info("Instalment verification delivered to a buyer");
    }

    /** Flexible contribution (3b). Factual and calm: nothing is ever due, so there is no "next" to chase. */
    @Override
    public void contributionVerified(ContributionAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject("Payment verified: " + alert.plotLabel() + " at " + alert.estateName());
        message.setText("""
                Hello,

                The developer has verified your payment of %s for %s, %s. You have now paid %s, and \
                %s is left. You can pay any amount, at any time, until it's finished.

                See your purchase:

                    %s
                """.formatted(naira(alert.amount()), alert.plotLabel(), alert.estateName(), naira(alert.paidSoFar()),
                naira(alert.remaining()), alert.purchaseUrl()));
        mailSender.send(message);
        log.info("Contribution verification delivered to a buyer");
    }

    /**
     * Flexible contribution, phase 4: a notice on a quiet plan, step by step.
     * Calm and factual, like the conflict guidance: what was found, what the
     * buyer can do, by when, and what ending the plan would mean in naira.
     */
    @Override
    public void quietPlan(QuietPlanAlert alert) {
        boolean finishBy = "finish_by".equals(alert.trigger());
        String plan = PlanNames.phrase(alert.planName()) + " for " + alert.plotLabel() + " (purchase "
                + alert.purchaseReference() + ")";
        String subject;
        String body;
        switch (alert.stage()) {
            case NOTICE -> {
                subject = "Notice on your plan for " + alert.plotLabel();
                body = """
                        The developer has given notice on %s: %s

                        %s If not, from the day after, the developer may end the plan on the terms for leaving: you \
                        would get back %s, and the developer keeps %s. You can also leave now on those same \
                        terms.

                        The developer's reason: %s
                        """.formatted(plan, finishBy
                                ? "the plan was due to be finished by " + alert.finishBy() + "."
                                : "no payment has been made since " + alert.lastPaymentOn() + ", and the plan allows "
                                + alert.inactivityMonths() + " months without one.",
                        finishBy ? "To keep the plot, finish the plan (" + naira(alert.remaining()) + " is left) by "
                                + alert.deadline() + "."
                                : "To keep the plan going, make a payment of " + (alert.minimumPayment() == null
                                ? "any amount" : "at least " + naira(alert.minimumPayment())) + " by " + alert.deadline()
                                + ".",
                        naira(alert.refund()), naira(alert.developerKeeps()), alert.note());
            }
            case CANCELLED -> {
                subject = "Notice withdrawn: your plan for " + alert.plotLabel();
                body = "The developer has withdrawn the notice on " + plan + ". Nothing changes; you can carry "
                        + "on paying any amount, at any time." + (alert.note() == null ? "" : "\n\nTheir note: "
                        + alert.note()) + "\n";
            }
            case CLEARED -> {
                subject = "Notice ended: your plan for " + alert.plotLabel();
                body = "The notice on " + plan + " has ended. " + alert.note() + "\n";
            }
            default -> {
                subject = "Your plan for " + alert.plotLabel() + " has ended";
                body = """
                        The developer has ended %s after the notice ran out, on the terms for leaving. You will \
                        get back %s; the developer keeps %s. HomeTrue sends the refund to you.
                        """.formatted(plan, naira(alert.refund()), naira(alert.developerKeeps()));
            }
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        message.setSubject(subject);
        message.setText("Hello,\n\n" + body + "\nSee your purchase:\n\n    " + alert.purchaseUrl() + "\n");
        mailSender.send(message);
        log.info("Quiet-plan {} delivered to a buyer", alert.stage().name().toLowerCase(Locale.ROOT));
    }

    /** Flexible contribution, phase 5: the figures, plainly, and the one thing to do. */
    @Override
    public void gainShareOwed(GainShareOwedAlert alert) {
        sendGainShare(alert.to(), "Good news: a share of the sale of " + alert.plotLabel() + " is yours", """
                Hello,

                %s at %s, which you left, has been sold again through HomeTrue for %s — %s more than your \
                agreed price of %s. Your share of that is %s%%: %s.%s

                Tell us which bank account to send it to, in your HomeTrue account:

                    %s
                """.formatted(alert.plotLabel(), alert.estateName(), naira(alert.salePrice()), naira(alert.gain()),
                naira(alert.agreedPrice()), alert.buyerSharePct(), naira(alert.amount()), alert.capped()
                        ? " (This is all the developer's payout from the sale could cover.)" : "", alert.url()));
    }

    @Override
    public void gainShareDeduction(GainShareDeductionAlert alert) {
        sendGainShare(alert.to(), "Payout note: " + alert.plotLabel() + " at " + alert.estateName(), """
                Hello,

                %s at %s (purchase %s) has sold for %s. An earlier buyer of the plot left a flexible plan with \
                a share of any later gain, as your declared terms set out, so %s is paid to them out of this \
                sale before your payout.
                """.formatted(alert.plotLabel(), alert.estateName(), alert.purchaseReference(), naira(alert.salePrice()),
                naira(alert.amount())));
    }

    @Override
    public void gainSharePaid(GainSharePaidAlert alert) {
        sendGainShare(alert.to(), "Your gain share is on its way", """
                Hello,

                HomeTrue has sent %s of your gain share to %s ending %s. Banks can take a little while to show it.

                    %s
                """.formatted(naira(alert.amount()), alert.bankName(), alert.accountLast4(), alert.url()));
    }

    private void sendGainShare(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
        log.info("Gain share email delivered");
    }

    /** Phase 5a. Factual: what was paid, what comes back, what the developer keeps, and what happens next. */
    @Override
    public void planWithdrawn(WithdrawalAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(alert.to());
        String figures = """
                Paid on the plan:            %s
                Returned to the buyer:       %s
                Kept by the developer:       %s
                """.formatted(naira(alert.paid()), naira(alert.refund()), naira(alert.developerKeeps()));
        if (alert.toDeveloper()) {
            message.setSubject("A buyer withdrew from their plan: " + alert.purchaseReference());
            message.setText("""
                    Hello,

                    The buyer on purchase %s has withdrawn from %s, on %s. The plot is back on sale.

                    %s
                    HomeTrue returns the buyer's part from the money it holds in escrow. Your shares already \
                    verified are paid out as usual.%s%s

                    See it under %s in your HomeTrue portal.
                    """.formatted(alert.purchaseReference(), planPhrase(alert),
                    alert.flexible() ? "the exit terms of that plan" : "the refund terms your estate declared", figures,
                    alert.awaitingFinance() == null ? "" : "\n\nOne payment is still waiting for your finance team to "
                            + "verify it. Your share of it, " + naira(alert.awaitingFinance()) + ", is held for you and "
                            + "paid out once it is verified.",
                    alert.reason() == null ? "" : "\n\nThe buyer's reason: " + alert.reason(),
                    alert.flexible() ? "Finance > Flexible plans" : "Finance > Plans"));
        } else {
            message.setSubject("You have withdrawn from your plan: " + alert.purchaseReference());
            message.setText("""
                    Hello,

                    You have withdrawn from %s for purchase %s, on the terms set out in your offer letter. The plot is no \
                    longer held for you.

                    %s
                    HomeTrue holds your part and returns it to you through the payment provider, payment by \
                    payment. We'll email you if we need a bank account from you.%s

                    See your purchase and the refund:

                        %s
                    """.formatted(planPhrase(alert), alert.purchaseReference(), figures,
                    alert.buyerGainSharePct() == null ? "" : "\n\nIf HomeTrue later sells the plot for more than "
                            + naira(alert.agreedPrice()) + ", you receive " + alert.buyerGainSharePct() + "% of the "
                            + "difference. We'll email you if that happens.",
                    alert.purchaseUrl()));
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

                            %s

                        If it is not paid by then, the developer may revoke the plan. The plot would no longer \
                        be held for you, and HomeTrue would return to you exactly what withdrawing returns:

                            Returned to you:         %s
                            Kept by the developer:   %s

                        You may also withdraw yourself at any time, on the same terms.%s

                        See your plan and pay:

                            %s
                        """.formatted(plot, alert.afterMonths(), alert.deadline(), naira(alert.totalDue()), naira(alert.refund()),
                        naira(alert.developerKeeps()), alert.note() == null ? "" : "\n\nThe developer's reason: " + alert.note(),
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

                            Returned to you:         %s
                            Kept by the developer:   %s

                        It is paid back through the payment provider, payment by payment. We'll email you if we \
                        need a bank account from you.

                            %s
                        """.formatted(plot, naira(alert.refund()), naira(alert.developerKeeps()), alert.purchaseUrl()));
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

                The developer has waived %s of the late-payment penalty on instalment %d of your instalment \
                plan for %s (purchase %s, %s).

                Their reason: %s

                Penalty still owed on that instalment today: %s. The instalment itself is still due; if it \
                stays unpaid long enough to reach a higher penalty step, the difference applies.

                    %s
                """.formatted(naira(alert.waived()), alert.instalmentSequence(), alert.plotLabel(), alert.purchaseReference(),
                alert.planName(), alert.reason(), naira(alert.stillOwed()), alert.purchaseUrl()));
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
        String what = instalment + " for " + alert.plotLabel() + ", " + alert.estateName() + ", " + naira(alert.amount());
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
                        ? " Paying it now, " + naira(alert.totalDue()) + ", brings your plan back up to date."
                        : " A late-payment penalty of " + naira(alert.penalty()) + " applies, as set out in your offer "
                        + "letter, so paying it now is " + naira(alert.totalDue()) + " and brings your plan back up "
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

    /** "the \"Pay as you can\" plan" for a flexible plan; "the instalment plan (12-month plan)" otherwise. */
    private static String planPhrase(WithdrawalAlert alert) {
        return alert.flexible() ? PlanNames.phrase(alert.planName()) : "the instalment plan (" + alert.planName() + ")";
    }

    /** Amounts reach this class as plain naira strings ("6500000.0000"); every email prints them as ₦6,500,000.00. */
    private static String naira(String plain) {
        return "₦" + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.UK))
                .format(new BigDecimal(plain).setScale(2, RoundingMode.HALF_UP));
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
