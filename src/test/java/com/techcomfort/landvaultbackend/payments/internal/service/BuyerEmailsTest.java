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
                "Double King", "15000000.00", true, null, URL, "HT-ALC-2026-000012", LETTER_URL));

        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("Good news: Block A, Plot 7 at Double King is yours");
        assertThat(message.getText()).contains("NGN 15000000.00").contains("verified").contains(URL)
                .contains("Your allocation letter, HT-ALC-2026-000012, is ready").contains(LETTER_URL);
    }

    @Test
    void withoutALetterTheEmailSaysNothingAboutOne() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "15000000.00", true, null, URL, null, null));

        assertThat(sent().getText()).contains(URL).doesNotContain("letter").doesNotContain("null");
    }

    @Test
    void aRejectedPurchaseSaysTheMoneyIsReturnedWithTheReasonAndNoDate() {
        sender.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert("b@example.com", "Block A, Plot 7",
                "Double King", "15000000.00", false, "Charged back by the bank.", URL, null, null));

        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("Your payment for Block A, Plot 7 is being returned");
        assertThat(message.getText()).contains("Charged back by the bank.").contains("full NGN 15000000.00")
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
