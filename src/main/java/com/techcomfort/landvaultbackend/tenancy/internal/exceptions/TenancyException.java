package com.techcomfort.landvaultbackend.tenancy.internal.exceptions;

import com.techcomfort.landvaultbackend.tenancy.internal.service.AdminTenantService;

/**
 * Base for {@link AdminTenantService}'s control-flow exceptions — nested
 * rather than five separate files, same convention as identity's
 * {@code AuthException} (small, closely related, only ever thrown/caught
 * within this one write flow).
 */
public abstract class TenancyException extends RuntimeException {

    /** No organization exists with the given id. */
    public static class TenantNotFound extends TenancyException {
    }

    /**
     * {@code organizations.rc_number} already belongs to another tenant —
     * checked proactively before any write, so a raw constraint violation
     * (the DB-level backstop for the concurrent-create race the proactive
     * check alone can't close) never reaches the caller either; see
     * {@code TenancyExceptionHandler}.
     */
    public static class DuplicateRcNumber extends TenancyException {
    }

    /** submit-documents called with zero {@code OrganizationDocument} rows on the tenant. */
    public static class NoDocumentsUploaded extends TenancyException {
    }

    /** resubmit's {@code documentId} doesn't belong to this tenant. */
    public static class DocumentNotFound extends TenancyException {
    }

    /** decision = REJECTED or REQUEST_MORE_INFO with a blank/missing reason. */
    public static class DecisionReasonRequired extends TenancyException {
    }

    /** status = SUSPENDED or OFFBOARDED with a blank/missing reason — see AGENTS.md Part 1 of tenancy slice B2. */
    public static class StatusReasonRequired extends TenancyException {
    }

    /**
     * A {@code TenantStatus} transition that isn't allowed — away from the
     * terminal {@code OFFBOARDED} state, or setting the same status the
     * tenant already has (a no-op, rejected rather than silently
     * succeeding since it usually means the caller has stale data). Kept
     * deliberately separate from {@link InvalidVerificationTransition} —
     * one exception per axis, so the two never get conflated even at the
     * exception-naming level. See AGENTS.md Part 0.
     */
    public static class InvalidStatusTransition extends TenancyException {

        private final String detail;

        public InvalidStatusTransition(String detail) {
            this.detail = detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    /**
     * submit-documents/begin-review/verification-decision called against a
     * {@code verificationState} that transition isn't valid from — e.g.
     * begin-review on a tenant still at {@code CREATED}.
     */
    public static class InvalidVerificationTransition extends TenancyException {

        private final String detail;

        public InvalidVerificationTransition(String detail) {
            this.detail = detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }
}
