package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The buyer emails' wording and link, and the notifier's after-commit / never-fail rules. */
class BuyerEmailsTest {

    private static final String URL = "http://localhost:8443/purchases/abc";
    private static final String LETTER_URL = "http://localhost:8443/documents?letter=def";

    private JavaMailSender mail;
    private EmailSettlementAlertSender sender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mail = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mail);
        sender = new EmailSettlementAlertSender(provider, "no-reply@landvault.test");
        sender.init();
    }

    @Test
    void aVerifiedPurchaseSaysThePlotIsYoursAndLinksToItAndItsLetter() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "15000000.00", true, null, URL, "HT-ALC-2026-000012", LETTER_URL, null));

        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("Good news: Block A, Plot 7 at Double King is yours");
        assertThat(message.getText()).contains("₦15,000,000.00").contains("verified").contains(URL)
                .contains("Your allocation letter, HT-ALC-2026-000012, is ready").contains(LETTER_URL);
    }

    /** Phase 3a: a verified deposit holds the plot on the plan — never "is yours". */
    @Test
    void aVerifiedDepositSaysThePlotIsHeldOnThePlanWithTheProvisionalLetter() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "4950000.00", true, null, URL, "HT-PAL-2026-000001", LETTER_URL, "12-month plan"));

        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("Deposit verified: Block A, Plot 7 at Double King is held for you");
        assertThat(message.getText()).contains("deposit of ₦4,950,000.00 on the 12-month plan")
                .contains("becomes yours when every instalment is paid and verified")
                .contains("Your provisional allocation letter, HT-PAL-2026-000001, is ready").contains(LETTER_URL)
                .doesNotContain("is yours");
    }

    /** A plan name that isn't "… plan" is quoted, and amounts at any scale print as naira. */
    @Test
    void aFlexibleFirstPaymentNamesThePlanAsAPlan() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "5000000.0000", true, null, URL, "HT-PAL-2026-000002", LETTER_URL, "Pay as you can",
                true, null));

        assertThat(sent().getText()).contains("first payment of ₦5,000,000.00 on the \"Pay as you can\" plan")
                .doesNotContain("NGN");
    }

    /** Found in the flexible-plan walkthrough: a plan's last payment says the plan is paid in full, with its total. */
    @Test
    void aCompletedPlanSaysItIsPaidInFull() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "6500000.00", true, null, URL, "HT-ALC-2026-000014", LETTER_URL, null, false,
                "16500000.00"));

        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("Good news: Block A, Plot 7 at Double King is yours");
        assertThat(message.getText()).contains("your plan is now paid in full (₦16,500,000.00)")
                .contains("payment of ₦6,500,000.00");
    }

    /** Flexible withdrawal: the plan's own terms, the gain share still to come, and the developer's own list. */
    @Test
    void aFlexibleWithdrawalNamesThePlanTheGainShareAndTheFlexiblePlansList() {
        sender.planWithdrawn(new SettlementAlertSender.WithdrawalAlert("b@example.com", false, "LV-1", "Pay as you can",
                "2000000.00", "1600000.00", "400000.00", null, null, URL, true, "15000000.00", "40"));
        sender.planWithdrawn(new SettlementAlertSender.WithdrawalAlert("ed@example.com", true, "LV-1", "Pay as you can",
                "2000000.00", "1600000.00", "400000.00", null, null, null, true, "15000000.00", "40"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(0).getText())
                .contains("withdrawn from the \"Pay as you can\" plan", "Returned to the buyer:       ₦1,600,000.00",
                        "sells the plot for more than ₦15,000,000.00, you receive 40% of the difference")
                .doesNotContain("instalment plan", "NGN");
        assertThat(captor.getAllValues().get(1).getText())
                .contains("on the exit terms of that plan", "Finance > Flexible plans")
                .doesNotContain("instalment plan", "your estate declared");
    }

    @Test
    void anInstalmentWithdrawalKeepsItsWordingAndNamesNoGainShare() {
        sender.planWithdrawn(new SettlementAlertSender.WithdrawalAlert("b@example.com", false, "LV-1", "12-month plan",
                "2000000.00", "1600000.00", "400000.00", null, null, URL, false, "15000000.00", null));

        assertThat(sent().getText()).contains("withdrawn from the instalment plan (12-month plan)")
                .doesNotContain("difference");
    }

    /** A notice says the least a payment may be, not "any amount", when the plan declares a minimum. */
    @Test
    void aQuietPlanNoticeStatesTheMinimumPayment() {
        sender.quietPlan(new SettlementAlertSender.QuietPlanAlert("b@example.com",
                SettlementAlertSender.RevocationStage.NOTICE, "LV-1", "Pay as you can", "Block C, Plot 6",
                "inactivity", 6, "27 March 2026", null, "14000000.00", "26 November 2026", "1200000.00",
                "300000.00", "No payment in 7 months.", URL, "100000.00"));

        assertThat(sent().getText()).contains("make a payment of at least ₦100,000.00 by 26 November 2026")
                .doesNotContain("any amount");
    }

    @Test
    void withoutALetterTheEmailSaysNothingAboutOne() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "15000000.00", true, null, URL, null, null, null));

        assertThat(sent().getText()).contains(URL).doesNotContain("letter").doesNotContain("null");
    }

    @Test
    void aRejectedPurchaseSaysTheMoneyIsReturnedWithTheReasonAndNoDate() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "15000000.00", false, "Charged back by the bank.", URL, null, null, null));

        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("Your payment for Block A, Plot 7 is being returned");
        assertThat(message.getText()).contains("Charged back by the bank.").contains("full ₦15,000,000.00")
                .contains(URL).doesNotContainIgnoringCase("days");
    }

    @Test
    void bothLatePaymentEmailsAndTheRefundAccountEmailLinkToThePurchase() {
        sender.latePaymentResolved(new SettlementAlertSender.LatePaymentAlert("b@example.com", "Plot 7", "Estate",
                "1.00", true, null, URL, "HT-ALC-2026-000013", LETTER_URL));
        sender.latePaymentResolved(new SettlementAlertSender.LatePaymentAlert("b@example.com", "Plot 7", "Estate",
                "1.00", false, "Sold to someone else", URL, null, null));
        sender.refundNeedsAccount(new SettlementAlertSender.RefundNeedsAccountAlert("b@example.com", "1.00",
                "LV-PAY-1", URL));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(3)).send(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(m -> assertThat(m.getText()).contains(URL));
        assertThat(captor.getAllValues().get(0).getText()).as("allocated late money names its letter")
                .contains("HT-ALC-2026-000013").contains(LETTER_URL);
        assertThat(captor.getAllValues().get(1).getText()).as("refunded late money has no letter")
                .doesNotContain("allocation letter");
    }

    @Test
    void thePurchaseLinkUsesTheCallbackUrlsOriginOnly() {
        BuyerNotifier notifier = new BuyerNotifier(new PaystackProperties(null, null,
                "https://app.landvault.com/payments/return", null, null));
        UUID id = UUID.randomUUID();

        assertThat(notifier.purchaseUrl(id)).isEqualTo("https://app.landvault.com/purchases/" + id);
        assertThat(notifier.letterUrl(id)).isEqualTo("https://app.landvault.com/documents?letter=" + id);
    }

    @Test
    void anEmailWaitsForCommitIsSkippedOnRollbackAndNeverThrows() {
        BuyerNotifier notifier = new BuyerNotifier(new PaystackProperties(null, null,
                "http://localhost:8443/payments/return", null, null));
        AtomicInteger sends = new AtomicInteger();

        TransactionSynchronizationManager.initSynchronization();
        try {
            notifier.afterCommit("test", sends::incrementAndGet);
            notifier.afterCommit("failing", () -> {
                throw new IllegalStateException("SMTP down");
            });
            assertThat(sends).as("nothing before commit").hasValue(0);
            var registered = TransactionSynchronizationManager.getSynchronizations();
            registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(sends).as("nothing on rollback").hasValue(0);
            assertThatCode(() -> registered.forEach(TransactionSynchronization::afterCommit))
                    .as("a failed email never escapes").doesNotThrowAnyException();
            assertThat(sends).hasValue(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captor.capture());
        return captor.getValue();
    }
}
