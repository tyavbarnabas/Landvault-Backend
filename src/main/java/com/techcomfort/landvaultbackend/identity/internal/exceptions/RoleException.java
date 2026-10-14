package com.techcomfort.landvaultbackend.identity.internal.exceptions;

/** Refusals when a company creates, edits or deletes its own roles, handled by {@link RoleExceptionHandler}. */
public abstract class RoleException extends RuntimeException {

    protected RoleException(String message) {
        super(message);
    }

    /** Also another company's role: never confirm it exists. */
    public static class NotFound extends RoleException {
        public NotFound() {
            super("Role not found.");
        }
    }

    public static class StandardRoleLocked extends RoleException {
        public StandardRoleLocked() {
            super("HomeTrue's standard roles can't be changed or deleted. Create your own role instead.");
        }
    }

    public static class CompanyWideOnly extends RoleException {
        public CompanyWideOnly() {
            super("Roles are managed company-wide. Switch to the whole-company view.");
        }
    }

    public static class UnknownPermission extends RoleException {
        public UnknownPermission(String codes) {
            super("Not a permission a company role can carry: " + codes + ".");
        }
    }

    /** SI-4: never grant what you don't hold. */
    public static class CannotGrant extends RoleException {
        public CannotGrant(String codes) {
            super("You can't put permissions you don't hold into a role: " + codes + ".");
        }
    }

    /** Editing a role that carries more than the editor holds would let them weaken someone stronger. */
    public static class Outranks extends RoleException {
        public Outranks() {
            super("This role carries permissions you don't hold, so you can't change it.");
        }
    }

    public static class CompanyWidePermissionInNarrowRole extends RoleException {
        public CompanyWidePermissionInNarrowRole(String codes) {
            super("These permissions only work for company-wide staff, so a role carrying them must have scope "
                    + "'company': " + codes + ".");
        }
    }

    public static class InvalidScope extends RoleException {
        public InvalidScope(String scope) {
            super("'" + scope + "' isn't a role scope. Use company, branch or either.");
        }
    }

    public static class ScopeImmutable extends RoleException {
        public ScopeImmutable() {
            super("A role's scope can't be changed, because people may already hold it that way. Create a new role.");
        }
    }

    public static class NameTaken extends RoleException {
        public NameTaken(String name) {
            super("Your company already has a role called '" + name + "'.");
        }
    }

    public static class LimitReached extends RoleException {
        public LimitReached(int limit) {
            super("A company can have at most " + limit + " roles of its own. Delete one you no longer use first.");
        }
    }

    public static class InUse extends RoleException {
        public InUse(String why) {
            super("This role can't be deleted: " + why + ".");
        }
    }

    public static class InvalidName extends RoleException {
        public InvalidName() {
            super("A role's name can't be blank.");
        }
    }

    public static class NoPermissions extends RoleException {
        public NoPermissions() {
            super("A role must carry at least one permission.");
        }
    }
}
