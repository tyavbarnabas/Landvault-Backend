package com.techcomfort.landvaultbackend.identity.internal.exceptions;

/** Staff-management refusals, handled by {@link InvitationExceptionHandler}. */
public abstract class StaffException extends RuntimeException {

    protected StaffException(String message) {
        super(message);
    }

    /** Also another company's user: never confirm they exist. */
    public static class NotFound extends StaffException {
        public NotFound() {
            super("Staff member not found.");
        }
    }

    public static class CompanyWideOnly extends StaffException {
        public CompanyWideOnly() {
            super("Staff are managed company-wide. Switch to the whole-company view.");
        }
    }

    public static class Yourself extends StaffException {
        public Yourself() {
            super("You can't change your own role or deactivate yourself. Ask another Executive Director.");
        }
    }

    public static class Outranks extends StaffException {
        public Outranks() {
            super("This person holds permissions you don't, so you can't change their access.");
        }
    }

    public static class LastExecutive extends StaffException {
        public LastExecutive() {
            super("This is your company's only active Executive Director. Invite or promote another one first.");
        }
    }

    public static class AlreadyDeactivated extends StaffException {
        public AlreadyDeactivated() {
            super("This person is already deactivated.");
        }
    }

    public static class NotDeactivated extends StaffException {
        public NotDeactivated() {
            super("Only a deactivated account can be reactivated.");
        }
    }
}
