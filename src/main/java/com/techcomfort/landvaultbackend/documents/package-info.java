/**
 * Documents HomeTrue issues — payment receipts today; offer and allocation
 * letters next. See AGENTS.md, "Documents".
 * <p>
 * A document is a stored copy of everything printed on it, written in the
 * same transaction as the event that issues it (a receipt: Paystack confirming
 * the payment, through payments' {@code PaymentSucceeded}). The PDF and JPEG
 * are drawn from that copy on demand, so they can always be reproduced and
 * never drift from what was issued. Every document carries a random
 * verification code in a QR code; the public verify route confirms it is
 * genuine without showing amounts or personal details.
 * <p>
 * Letterheads (the company's and each branch's: signatory, signature, seal,
 * logo) and each estate's allocation conditions are set here too, for the
 * letters to come.
 * <p>
 * Depends on {@code payments} (the event; refunds, through {@code PaymentsApi};
 * and it implements payments' {@code ReceiptLookup}), {@code portfolio} (which
 * purchase a portfolio entry is), {@code tenancy} (company and branch offices), {@code inventory} (an estate's
 * facts and cost disclosure, through {@code EstateDocumentsApi}),
 * {@code storage} (letterhead images), {@code audit} and {@code common}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Documents"
)
package com.techcomfort.landvaultbackend.documents;
