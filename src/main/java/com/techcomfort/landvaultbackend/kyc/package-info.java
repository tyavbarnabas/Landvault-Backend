/**
 * Purchase-time identity verification.
 * <p>
 * Verification gates <em>buying</em>, never signup or browsing: nobody is
 * asked for a passport to look at land. It is held at <strong>platform
 * level</strong> — a buyer has no tenant, verifies once, and transacts with
 * every company on the marketplace. That is the structural reason buyers are
 * not tenant-scoped, not an incidental consequence of it.
 * <p>
 * Public surface:
 * {@link com.techcomfort.landvaultbackend.kyc.KycApi} (one boolean, used by
 * {@code checkout} to gate a reservation) and the DTOs in {@code kyc.dto}.
 * This module also implements {@code identity}'s
 * {@link com.techcomfort.landvaultbackend.identity.BuyerKycStatusProvider},
 * which is how the login response carries a real status without
 * {@code identity} depending on this module — dependency inversion, because
 * this module already depends on {@code identity} for a buyer's country.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "KYC"
)
package com.techcomfort.landvaultbackend.kyc;
