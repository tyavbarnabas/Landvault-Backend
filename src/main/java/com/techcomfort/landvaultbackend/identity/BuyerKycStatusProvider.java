package com.techcomfort.landvaultbackend.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * How {@code identity} learns a buyer's verification status for the login
 * response, without depending on the module that owns it.
 * <p>
 * <strong>Dependency inversion, and the direction is the whole point.</strong>
 * {@code kyc} needs {@code IdentityApi} (a buyer's country decides which
 * documents they must produce), so {@code kyc → identity} already exists;
 * {@code identity} calling into {@code kyc} for this one field would close
 * that into a cycle. Declaring the interface here and implementing it there
 * keeps every arrow pointing the way it already did — the same technique as
 * {@code audit}'s {@code ActorNameResolver} and {@code conflicts}'
 * {@code EstateLabelResolver}, now the fifth use.
 * <p>
 * Returns the wire status string rather than an enum: the enum belongs to
 * {@code kyc}, and importing it here would defeat the inversion.
 */
public interface BuyerKycStatusProvider {

    /**
     * Empty when the buyer has no verification record at all — which the
     * caller reports as {@code unsubmitted}. An absent record is genuinely
     * "not started"; nothing is written until a buyer submits.
     */
    Optional<String> kycStatusOf(UUID userId);
}
