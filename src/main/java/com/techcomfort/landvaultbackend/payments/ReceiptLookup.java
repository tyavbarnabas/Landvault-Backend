package com.techcomfort.landvaultbackend.payments;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Declared here, implemented by the documents module — the inverted-interface
 * pattern (see AGENTS.md): documents depends on payments, so payments can't
 * call documents. Lets a purchase's payment summary name its receipt.
 * Batched; a payment with no receipt is absent from the map.
 */
public interface ReceiptLookup {

    Map<UUID, ReceiptRef> receiptsFor(Collection<UUID> paymentIds);

    /** A receipt's id and printed number. */
    record ReceiptRef(UUID id, String number) {
    }
}
