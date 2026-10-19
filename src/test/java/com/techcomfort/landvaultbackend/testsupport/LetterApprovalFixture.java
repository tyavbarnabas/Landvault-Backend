package com.techcomfort.landvaultbackend.testsupport;

import java.util.UUID;

/**
 * Gives an estate an approved, current allocation letter straight in SQL — a
 * company signatory with a signature file, HomeTrue-approved conditions, and
 * an approval recording today's versions — for tests that need an estate to
 * be publishable or a sale to be verifiable (changeset 095) but aren't about
 * letters and run no storage. Run it last, after fees and terms are declared:
 * a later change to them lapses the approval, as it would for real. Run it as
 * the superuser, like the other fixture SQL.
 */
public final class LetterApprovalFixture {

    private LetterApprovalFixture() {
    }

    public static String approvedLetterSql(UUID estateId) {
        return """
                WITH sig AS (
                    INSERT INTO stored_files (id, tenant_id, created_at, deleted, visibility, bucket, object_key,
                                              purpose, file_name, file_type, size_bytes, status, uploaded_by,
                                              upload_link_expires_at, confirmed_at)
                    SELECT gen_random_uuid(), e.tenant_id, now(), false, 'PRIVATE', 'test-private',
                           'letterhead-signature/' || gen_random_uuid() || '.png', 'letterhead-signature',
                           'signature.png', 'PNG', 1, 'READY', (SELECT id FROM users ORDER BY created_at LIMIT 1),
                           now(), now()
                    FROM estates e WHERE e.id = '%1$s'
                      AND NOT EXISTS (SELECT 1 FROM letterheads l
                                       WHERE l.tenant_id = e.tenant_id AND l.branch_id IS NULL AND l.deleted = false)
                    RETURNING id, tenant_id
                )
                INSERT INTO letterheads (id, tenant_id, branch_id, created_at, deleted, signatory_name, signatory_title,
                                         signature_file_id, version)
                SELECT gen_random_uuid(), sig.tenant_id, NULL, now(), false, 'Fixture Signatory', 'Managing Director',
                       sig.id, 1
                FROM sig;

                WITH t AS (
                    INSERT INTO estate_letter_terms (id, created_at, tenant_id, branch_id, estate_id, version, clauses,
                                                     author_user_id)
                    SELECT gen_random_uuid(), now(), e.tenant_id, e.branch_id, e.id,
                           coalesce((SELECT max(version) FROM estate_letter_terms WHERE estate_id = e.id), 0) + 1,
                           ARRAY['The plot is for residential use only.'],
                           (SELECT id FROM users ORDER BY created_at LIMIT 1)
                    FROM estates e WHERE e.id = '%1$s'
                    RETURNING id, tenant_id, branch_id, estate_id, version
                )
                INSERT INTO estate_letter_terms_reviews (id, created_at, tenant_id, branch_id, estate_id, terms_id,
                                                         terms_version, decision, reviewer_user_id)
                SELECT gen_random_uuid(), now(), t.tenant_id, t.branch_id, t.estate_id, t.id, t.version, 'APPROVED',
                       (SELECT id FROM users ORDER BY created_at LIMIT 1)
                FROM t;

                INSERT INTO estate_letter_approvals (id, created_at, tenant_id, branch_id, estate_id, action,
                                                     actor_user_id, terms_version, company_letterhead_version,
                                                     branch_letterhead_version, fees_version, refund_terms_version,
                                                     default_terms_version, template_source, template_version)
                SELECT gen_random_uuid(), clock_timestamp(), e.tenant_id, e.branch_id, e.id, 'APPROVED',
                       (SELECT id FROM users ORDER BY created_at LIMIT 1),
                       (SELECT max(terms_version) FROM estate_letter_terms_reviews
                         WHERE estate_id = e.id AND decision = 'APPROVED'),
                       (SELECT version FROM letterheads
                         WHERE tenant_id = e.tenant_id AND branch_id IS NULL AND deleted = false),
                       CASE WHEN e.branch_id IS NULL THEN NULL
                            ELSE coalesce((SELECT version FROM letterheads WHERE tenant_id = e.tenant_id
                                             AND branch_id = e.branch_id AND deleted = false), 0) END,
                       CASE WHEN e.fees_declared_at IS NULL THEN NULL ELSE e.fees_version END,
                       (SELECT max(version) FROM estate_refund_terms WHERE estate_id = e.id AND deleted = false),
                       (SELECT max(version) FROM estate_default_terms WHERE estate_id = e.id AND deleted = false),
                       'default', NULL
                FROM estates e WHERE e.id = '%1$s'
                """.formatted(estateId);
    }
}
