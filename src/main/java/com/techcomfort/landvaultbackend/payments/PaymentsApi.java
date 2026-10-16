package com.techcomfort.landvaultbackend.payments;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** What other modules may ask the payments module. */
public interface PaymentsApi {

    /**
     * When each payment's money was returned to the buyer: Paystack reported
     * the refund processed. Payments with no processed refund are absent.
     */
    Map<UUID, Instant> refundedAt(Collection<UUID> paymentIds);
}
