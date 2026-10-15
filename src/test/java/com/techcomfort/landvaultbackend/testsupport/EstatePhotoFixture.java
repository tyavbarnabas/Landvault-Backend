package com.techcomfort.landvaultbackend.testsupport;

import java.util.UUID;

/**
 * Gives an estate one cover photo straight in SQL — a ready public stored file
 * and an {@code estate_photos} row — for tests that need an estate to be
 * publishable (changeset 086) but aren't about photos and run no storage.
 * Does nothing when the estate already has a photo. Run it as the superuser,
 * like the other fixture SQL.
 */
public final class EstatePhotoFixture {

    private EstatePhotoFixture() {
    }

    public static String coverPhotoSql(UUID estateId) {
        return """
                WITH f AS (
                    INSERT INTO stored_files (id, tenant_id, created_at, deleted, visibility, bucket, object_key,
                                              purpose, file_name, file_type, size_bytes, status, uploaded_by,
                                              upload_link_expires_at, confirmed_at)
                    SELECT gen_random_uuid(), e.tenant_id, now(), false, 'PUBLIC', 'test-public',
                           'estate-photo/' || gen_random_uuid() || '.jpg', 'estate-photo', 'fixture.jpg', 'JPEG', 1,
                           'READY', (SELECT id FROM users ORDER BY created_at LIMIT 1), now(), now()
                    FROM estates e WHERE e.id = '%1$s'
                      AND NOT EXISTS (SELECT 1 FROM estate_photos p WHERE p.estate_id = e.id AND p.deleted = false)
                    RETURNING id
                )
                INSERT INTO estate_photos (id, tenant_id, branch_id, created_at, deleted, estate_id, file_id, position, cover)
                SELECT gen_random_uuid(), e.tenant_id, e.branch_id, now(), false, e.id, f.id, 0, true
                FROM f, estates e WHERE e.id = '%1$s'
                """.formatted(estateId);
    }
}
