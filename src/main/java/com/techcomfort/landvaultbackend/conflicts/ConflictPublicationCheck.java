package com.techcomfort.landvaultbackend.conflicts;

/**
 * The conflict module's answer to "may this estate go on the marketplace?"
 * (CD-10) — the fifth condition beside the four in AGENTS.md's publication
 * gate, never a replacement for them.
 * <p>
 * <strong>Blocking and warning are separate fields on purpose.</strong> A
 * {@code HIGH} conflict blocks: two companies claim the same ground and a
 * buyer could pay the wrong one. A {@code MEDIUM} conflict warns only — one
 * company's own two estates overlapping is an internal survey problem that
 * puts no buyer at risk, and blocking trade over it would be
 * disproportionate. Collapsing these into a single boolean would force that
 * distinction to be re-decided (probably differently) by whoever builds the
 * gate.
 * <p>
 * Carries no counterparty identity, deliberately: this is consumed by a
 * tenant-facing publication flow, and the same non-disclosure rule applies
 * here as in {@code TenantConflictDto}.
 */
public record ConflictPublicationCheck(
        boolean blocked,
        String blockReason,
        int blockingConflictCount,
        int warningConflictCount
) {

    public static ConflictPublicationCheck clear() {
        return new ConflictPublicationCheck(false, null, 0, 0);
    }

    public static ConflictPublicationCheck warning(int warningConflictCount) {
        return new ConflictPublicationCheck(false, null, 0, warningConflictCount);
    }

    public static ConflictPublicationCheck blocked(
            String blockReason, int blockingConflictCount, int warningConflictCount) {
        return new ConflictPublicationCheck(true, blockReason, blockingConflictCount, warningConflictCount);
    }
}
