package com.techcomfort.landvaultbackend.testsupport;

import java.util.UUID;

/**
 * Gives an estate a title HomeTrue has verified, and the matching verified
 * title check, straight in SQL — for tests that read titles but aren't about
 * declaring or reviewing them and run no storage (changeset 087). No
 * documents: none of them opens one. Run it as the superuser.
 */
public final class EstateTitleFixture {

    private EstateTitleFixture() {
    }

    public static String verifiedTitleSql(UUID estateId, String titleNumber, String checkNote) {
        return """
                INSERT INTO estate_titles (id, tenant_id, branch_id, created_at, deleted, estate_id, title_type,
                                           title_number, issued_date, status, submitted_at, decided_at,
                                           verification_source)
                SELECT gen_random_uuid(), e.tenant_id, e.branch_id, now(), false, e.id, 'C_OF_O', '%2$s',
                       DATE '2024-01-01', 'VERIFIED', now(), now(), 'MANUAL_REVIEW'
                FROM estates e WHERE e.id = '%1$s';
                INSERT INTO estate_verification_checks (id, tenant_id, branch_id, created_at, deleted, estate_id,
                                                        check_type, status, verification_source, last_verified_at, notes)
                SELECT gen_random_uuid(), e.tenant_id, e.branch_id, now(), false, e.id, 'TITLE_VERIFICATION',
                       'VERIFIED', 'MANUAL_REVIEW', now(), '%3$s'
                FROM estates e WHERE e.id = '%1$s';
                """.formatted(estateId, titleNumber, checkNote);
    }
}
