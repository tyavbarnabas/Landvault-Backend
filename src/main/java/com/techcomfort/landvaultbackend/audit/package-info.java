/**
 * The platform-wide, append-only audit trail — cross-cutting by design,
 * since every module will eventually record actions here (tenant
 * lifecycle today; support access, plan/status changes, and more as later
 * slices land). Not a shared/open module like {@code common} — it has its
 * own real behavior (writing entries), just consumed from many places.
 * <p>
 * The only public surface is {@link com.techcomfort.landvaultbackend.audit.AuditApi}
 * and {@link com.techcomfort.landvaultbackend.audit.AuditEntryRequest} —
 * other modules log through {@code AuditApi.record(...)}, never by writing
 * to {@code audit_log_entries} directly. See AGENTS.md.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Audit"
)
package com.techcomfort.landvaultbackend.audit;
