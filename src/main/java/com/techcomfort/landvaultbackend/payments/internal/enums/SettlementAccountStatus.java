package com.techcomfort.landvaultbackend.payments.internal.enums;

import java.util.Locale;

/** One payout-account submission's state. Persisted by name; the wire value is lowercase. */
public enum SettlementAccountStatus {
    /** Submitted by the company, waiting for a Super Admin. Never paid to. */
    PENDING,
    /** Approved, with a Paystack recipient code — the only account payouts use. */
    APPROVED,
    REJECTED,
    /** The company took it back before a decision. */
    WITHDRAWN,
    /** Was approved; replaced by a newer approved account. Kept as history. */
    SUPERSEDED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
