package com.techcomfort.landvaultbackend.tenancy.internal.exceptions;

/** {@code PortalBranchService}'s refusals, handled by {@link PortalBranchExceptionHandler}. */
public abstract class PortalBranchException extends RuntimeException {

    public static class NotFound extends PortalBranchException {
    }

    public static class NoTenant extends PortalBranchException {
    }

    /** TB-3: a caller scoped to one branch may not create or rename branches. */
    public static class CompanyWideOnly extends PortalBranchException {
    }

    public static class BlankName extends PortalBranchException {
    }

    public static class UnknownState extends PortalBranchException {

        private final String detail;

        public UnknownState(String input, java.util.List<String> names) {
            this.detail = "'" + input.trim() + "' isn't a Nigerian state. Use one of: " + names + ".";
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    public static class NameTaken extends PortalBranchException {

        private final String name;

        public NameTaken(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }
}
