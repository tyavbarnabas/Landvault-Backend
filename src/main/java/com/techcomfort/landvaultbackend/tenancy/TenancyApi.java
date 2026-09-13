package com.techcomfort.landvaultbackend.tenancy;

/**
 * The tenancy module's only public surface.
 * <p>
 * Empty for now: there is no {@code Tenant} entity or repository yet (the
 * next task), so there's nothing to expose. Kept present anyway so the
 * module boundary — and the rule that other modules go through this
 * interface rather than {@code tenancy.internal} — is established before
 * the first entity lands. Add methods here as other modules genuinely need
 * a cross-module read; never widen access by exposing
 * {@code tenancy.internal} types directly. See AGENTS.md.
 */
public interface TenancyApi {
}
