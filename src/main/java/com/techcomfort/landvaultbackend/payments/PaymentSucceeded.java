package com.techcomfort.landvaultbackend.payments;

import java.util.UUID;

/**
 * Paystack confirmed a payment for exactly what was asked (PY-8). Published
 * inside the confirming transaction, so a plain synchronous listener commits
 * or rolls back with it — the documents module issues the receipt this way,
 * so no succeeded payment is ever without one. Never asynchronous or
 * after-commit. See AGENTS.md, "Documents".
 */
public record PaymentSucceeded(UUID paymentId) {
}
