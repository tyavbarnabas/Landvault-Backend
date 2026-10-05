package com.techcomfort.landvaultbackend.identity.internal.exceptions;

/** Staff-invitation refusals, handled by {@link InvitationExceptionHandler}. */
public abstract class InvitationException extends RuntimeException {

    protected InvitationException(String message) {
        super(message);
    }

    /** One answer for unknown, expired, revoked and used — SI-5: distinguishing them tells an attacker which tokens existed. */
    public static class Invalid extends InvitationException {
        public Invalid() {
            super("This invitation link is invalid or has expired. Ask your company to send a new one.");
        }
    }

    public static class NotFound extends InvitationException {
        public NotFound() {
            super("Invitation not found.");
        }
    }

    public static class CompanyWideOnly extends InvitationException {
        public CompanyWideOnly() {
            super("Staff are invited company-wide. Switch to the whole-company view to invite.");
        }
    }

    public static class TenantNotActive extends InvitationException {
        public TenantNotActive() {
            super("Your organization's account is not active, so invitations can't be sent or accepted right now.");
        }
    }

    public static class RoleNotInvitable extends InvitationException {
        public RoleNotInvitable(String roleCode) {
            super("'" + roleCode + "' isn't a role a company can assign.");
        }
    }

    /** SI-4: never grant what you don't hold. */
    public static class CannotGrant extends InvitationException {
        public CannotGrant(String roleCode) {
            super("You can't invite someone as '" + roleCode + "': it carries permissions you don't hold.");
        }
    }

    public static class ScopeMismatch extends InvitationException {
        public ScopeMismatch(String message) {
            super(message);
        }
    }

    public static class BranchNotFound extends InvitationException {
        public BranchNotFound() {
            super("That branch isn't one of your company's branches.");
        }
    }

    public static class EmailHasAccount extends InvitationException {
        public EmailHasAccount() {
            super("That email already has a LandVault account. Staff need a separate work email — an existing "
                    + "account can't also be given a company role.");
        }
    }

    public static class AlreadyPending extends InvitationException {
        public AlreadyPending() {
            super("There's already an open invitation or request for that email.");
        }
    }

    public static class NotOpen extends InvitationException {
        public NotOpen() {
            super("That invitation was already accepted, revoked or rejected.");
        }
    }

    public static class BranchScopeOnly extends InvitationException {
        public BranchScopeOnly() {
            super("Requests are made from a branch. Company-wide staff invite directly instead.");
        }
    }

    public static class NotAwaitingApproval extends InvitationException {
        public NotAwaitingApproval() {
            super("That invitation isn't a request waiting for approval.");
        }
    }

    public static class RequestExpired extends InvitationException {
        public RequestExpired() {
            super("This request waited too long and has lapsed. Reject it, and the branch can ask again.");
        }
    }

    public static class AwaitingApproval extends InvitationException {
        public AwaitingApproval() {
            super("This request hasn't been approved yet, so there's no invitation to resend.");
        }
    }

    public static class ResendTooSoon extends InvitationException {
        public ResendTooSoon() {
            super("This invitation was sent moments ago. Wait a couple of minutes before resending.");
        }
    }

    public static class ResendLimitReached extends InvitationException {
        public ResendLimitReached() {
            super("This invitation has been sent the maximum number of times. Revoke it and create a new one if needed.");
        }
    }
}
