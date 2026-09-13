/**
 * Owns user identity: the single {@code users} table shared by every kind
 * of platform participant (buyer, tenant staff, platform staff, independent
 * agent), plus the display preferences attached to an account.
 * <p>
 * Does not own tenant membership, verification, or branch structure — see
 * the {@code tenancy} module for that. A user with {@code tenantId} set is
 * tenant staff; this module doesn't interpret what that tenant is, only
 * that it's an opaque foreign id.
 * <p>
 * The only public surface is {@link com.techcomfort.landvaultbackend.identity.IdentityApi}
 * and the DTOs in {@code identity.dto} — everything under
 * {@code identity.internal} (entities, and later repositories/services) is
 * invisible to every other module.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity"
)
package com.techcomfort.landvaultbackend.identity;
