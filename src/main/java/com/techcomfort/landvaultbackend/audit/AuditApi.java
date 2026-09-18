package com.techcomfort.landvaultbackend.audit;

/**
 * The audit module's only public surface. Other modules log through this,
 * never by writing to {@code audit_log_entries} directly — a public
 * repository would be a public database handle, same reasoning as every
 * other module's boundary. See AGENTS.md.
 */
public interface AuditApi {

    /**
     * Writes one append-only entry. Participates in the caller's own
     * transaction (this is a plain {@code @Transactional} call, not an
     * event) — if the caller's transaction rolls back, so does this entry.
     * That's deliberate: an audit entry for a write that never actually
     * happened would be worse than no entry at all.
     */
    void record(AuditEntryRequest request);
}
