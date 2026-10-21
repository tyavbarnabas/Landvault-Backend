package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.util.UUID;

/**
 * Buyer emails about a decision: sent only once the decision has committed,
 * and a failed email never undoes or fails it. Every one links to the
 * purchase (and an allocation to its letter), on the frontend whose origin {@code PAYSTACK_CALLBACK_URL} names.
 * See AGENTS.md, "Buyers are emailed about every decision".
 */
@Slf4j
@Component
public class BuyerNotifier {

    private final String frontendBase;

    public BuyerNotifier(PaystackProperties paystack) {
        URI callback = URI.create(paystack.callbackUrl());
        this.frontendBase = callback.getScheme() + "://" + callback.getRawAuthority();
    }

    public String purchaseUrl(UUID transactionId) {
        return frontendBase + "/purchases/" + transactionId;
    }

    /** The document vault, naming the letter so the page can open it; the PDF itself needs the buyer signed in. */
    public String letterUrl(UUID letterId) {
        return frontendBase + "/documents?letter=" + letterId;
    }

    /** After commit when a transaction is open (never for a rolled-back decision), otherwise now. */
    public void afterCommit(String what, Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendQuietly(what, send);
                }
            });
        } else {
            sendQuietly(what, send);
        }
    }

    private static void sendQuietly(String what, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            // The exception's class only: a mail error's message can carry the recipient's address.
            log.warn("Buyer email '{}' could not be sent ({}); the decision stands", what, e.getClass().getSimpleName());
        }
    }
}
