package com.techcomfort.landvaultbackend.kyc;

import java.util.UUID;

/**
 * The kyc module's public surface for other modules.
 * <p>
 * Deliberately one boolean. {@code checkout} needs to know whether a buyer
 * may proceed to a purchase and nothing else — handing it a status enum or a
 * record would let a gate somewhere reason about {@code SUBMITTED} versus
 * {@code UNDER_REVIEW} and quietly invent a policy this module owns. It also
 * keeps an internal enum from crossing the boundary (AGENTS.md).
 */
public interface KycApi {

    /**
     * True only for an approved verification. A buyer with no record, a
     * pending one, or a rejected one is not verified — all four cases are
     * the same answer to the only question the caller is asking.
     */
    boolean isVerified(UUID userId);
}
