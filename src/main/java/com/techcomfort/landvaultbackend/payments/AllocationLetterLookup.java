package com.techcomfort.landvaultbackend.payments;

import java.util.Optional;
import java.util.UUID;

/**
 * Declared here, implemented by the documents module — the inverted-interface
 * pattern (see AGENTS.md), like {@link ReceiptLookup}. Lets a finance decision
 * name the allocation letter it just issued.
 */
public interface AllocationLetterLookup {

    Optional<LetterRef> letterFor(UUID transactionId);

    /** A letter's id and printed number. */
    record LetterRef(UUID id, String number) {
    }
}
