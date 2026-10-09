package com.techcomfort.landvaultbackend.checkout;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Declared here, implemented by the payments module — the inverted-interface
 * pattern (see AGENTS.md): payments depends on checkout, so checkout calling
 * payments would be a cycle. Batched: a page of purchases is summarised in one
 * call. A purchase with no payment yet is simply absent from the map.
 */
public interface PaymentSummaryProvider {

    Map<UUID, PaymentSummary> summariesFor(Collection<UUID> transactionIds);
}
