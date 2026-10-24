package com.techcomfort.landvaultbackend.payments;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 5c: the restructure letter ({@code HT-RST-}) issued with a developer's
 * proposal. Declared here and implemented by documents, which already depends
 * on payments — the inverted-interface pattern (see AGENTS.md).
 */
public interface RestructureLetters {

    /**
     * Issues the letter in the caller's transaction, so the proposal and its
     * letter commit or roll back together. Empty when the estate's letter
     * setup isn't approved and current — the proposal is then refused.
     */
    Optional<LetterRef> issue(RestructureLetterRequest request);

    record LetterRef(UUID id, String number) {
    }
}
