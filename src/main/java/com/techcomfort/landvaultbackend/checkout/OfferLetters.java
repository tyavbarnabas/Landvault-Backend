package com.techcomfort.landvaultbackend.checkout;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The offer letter a purchase is opened with (OL). Declared here and
 * implemented by documents, which already depends on checkout — the tenth
 * inverted interface. See AGENTS.md, "Documents: the offer letter".
 */
public interface OfferLetters {

    /**
     * Issues the offer for a purchase opened in the caller's transaction, so
     * the two commit or roll back together. Empty when the estate's letter
     * isn't approved and current — checkout then refuses the purchase.
     */
    Optional<OfferSummary> issue(NewPurchase purchase);

    /** Each purchase's offer, by transaction id; purchases opened before offers existed have none. */
    Map<UUID, OfferSummary> forTransactions(Collection<UUID> transactionIds);
}
