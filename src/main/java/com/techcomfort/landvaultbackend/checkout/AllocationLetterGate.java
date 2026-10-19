package com.techcomfort.landvaultbackend.checkout;

import java.util.UUID;

/**
 * Whether an estate's allocation letter can be issued now — approved, still
 * current, nothing missing. Finance can't allocate without it, because the
 * letter is issued in the same transaction as the sale (AL). Declared here
 * and implemented by documents, which already depends on checkout — the
 * eighth inverted interface. Read under the caller's own scope.
 */
public interface AllocationLetterGate {

    boolean letterReady(UUID estateId);
}
