package com.techcomfort.landvaultbackend.common;

/**
 * Thrown when a write would violate the platform-wide, case-insensitive
 * email-uniqueness guarantee ({@code idx_users_email_lower} — see
 * {@code 002-create-users-table.xml}). Lives in {@code common}, not
 * {@code identity}, specifically so a module that can't import
 * {@code identity} (e.g. {@code tenancy}, for the same cycle-avoidance
 * reason as {@code TenantStaffAccountRequested}) can still catch it and
 * report a correct error — {@code identity}'s own registration flow keeps
 * using its own {@code AuthException.EmailAlreadyRegistered} instead; this
 * type exists for everyone else.
 */
public class DuplicateEmailException extends RuntimeException {
}
