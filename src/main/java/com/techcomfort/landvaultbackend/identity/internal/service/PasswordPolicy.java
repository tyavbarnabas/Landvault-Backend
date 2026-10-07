package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.internal.exceptions.WeakPasswordException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The one password rule, applied wherever a password is <em>set</em> —
 * registration, reset, change, invitation — and never at login, so existing
 * passwords keep working. <strong>Must stay identical to the frontend's
 * {@code src/lib/passwordPolicy.ts}</strong>: same checks, same order, same
 * sentences. Changing one means changing both. See AGENTS.md.
 * <p>
 * Never logs or echoes the password; the sentences name the rule only.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    /** The frontend caps input here. */
    public static final int MAX_LENGTH = 64;
    /** BCrypt can't hash more than 72 bytes; Spring Security 7 throws rather than truncating. */
    public static final int MAX_BYTES = 72;

    public static final String TOO_SHORT = "Use at least 8 characters.";
    public static final String TOO_LONG = "Use at most 64 characters.";
    /** Under 64 characters but over BCrypt's 72 bytes — only accented or special characters can do that. */
    public static final String TOO_MANY_BYTES = "That password is too long — use fewer accented or special characters.";
    public static final String LETTERS_AND_NUMBER = "Use letters and at least one number.";
    public static final String TOO_COMMON = "That password is too common — it's among the first ones attackers try.";
    public static final String PERSONAL = "Don't use your name or email in your password.";
    public static final String SAME_AS_CURRENT = "Choose a password different from your current one.";

    // Lowercase; compared against the whole password and its "core" (leading
    // and trailing non-letters removed). Same list as the frontend.
    private static final Set<String> COMMON = Set.of(
            "password", "passw0rd", "qwerty", "qwertyuiop", "abc", "abcdef", "letmein", "welcome", "admin", "iloveyou",
            "monkey", "dragon", "football", "baseball", "sunshine", "princess", "master", "shadow", "trustno",
            "changeme", "default", "secret", "login", "starwars", "whatever", "landvault", "nigeria", "naija",
            "lagos", "abuja", "jesus", "godisgood", "blessed", "chelsea", "arsenal", "manutd");

    private PasswordPolicy() {
    }

    /** The first rule the password breaks, as the sentence to show — or null if it is acceptable. */
    public static String problemWith(String password, String email, String firstName, String lastName) {
        String p = password == null ? "" : password;
        if (p.length() < MIN_LENGTH) {
            return TOO_SHORT;
        }
        if (p.length() > MAX_LENGTH) {
            return TOO_LONG;
        }
        if (p.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return TOO_MANY_BYTES;
        }
        if (!p.matches("(?s).*[a-zA-Z].*") || !p.matches("(?s).*[0-9].*")) {
            return LETTERS_AND_NUMBER;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        String core = lower.replaceAll("[^a-z]+$", "").replaceAll("^[^a-z]+", "");
        if (COMMON.contains(lower) || COMMON.contains(core) || p.matches("(.)\\1+")
                || lower.startsWith("0123") || lower.startsWith("1234") || lower.startsWith("abcd")) {
            return TOO_COMMON;
        }
        String localPart = email == null ? null : email.split("@", 2)[0];
        boolean personal = Stream.of(localPart, firstName, lastName)
                .filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> s.length() >= 3)
                .anyMatch(lower::contains);
        return personal ? PERSONAL : null;
    }

    /** Refuses a password that breaks the rule, reported against {@code field} (the request's own field name). */
    public static void require(String password, String field, String email, String firstName, String lastName) {
        String problem = problemWith(password, email, firstName, lastName);
        if (problem != null) {
            throw new WeakPasswordException(field, problem);
        }
    }
}
