package com.techcomfort.landvaultbackend.documents.internal.render;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fill-in fields a letter template may use, and HomeTrue's own wording for
 * a company that hasn't written any. Only these fields exist: a template can
 * place facts HomeTrue holds, never invent a new one (there is no
 * {@code {amount}} — fees come from the cost disclosure).
 */
public final class LetterPlaceholders {

    public static final List<String> FIELDS =
            List.of("buyerName", "plot", "estate", "location", "applicationDate", "allocationDate");
    /** The opening must name what is allocated. */
    public static final List<String> REQUIRED_IN_OPENING = List.of("plot", "estate");

    public static final String DEFAULT_OPENING =
            "We are pleased to allocate to you {plot} at {estate}, {location}, subject to the following terms and conditions.";
    public static final String DEFAULT_SIGN_OFF = "Congratulations.";

    private static final Pattern FIELD = Pattern.compile("\\{([^{}]*)}");

    private LetterPlaceholders() {
    }

    /** Fields used in {@code text} that don't exist, in order of appearance. */
    public static Set<String> unknown(String text) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(text == null ? "" : text);
        while (m.find()) {
            if (!FIELDS.contains(m.group(1))) {
                unknown.add("{" + m.group(1) + "}");
            }
        }
        return unknown;
    }

    public static boolean uses(String text, String field) {
        return text != null && text.contains("{" + field + "}");
    }

    /** The text with every field removed — what the money and bank checks read. */
    public static String withoutFields(String text) {
        return text == null ? null : FIELD.matcher(text).replaceAll(" ");
    }

    /** Fills each field from {@code values}; a field with no value (a location nobody entered) is dropped cleanly. */
    public static String fill(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        Matcher m = FIELD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        // A dropped field can leave ", ," or a space before punctuation behind.
        return out.toString().replaceAll("\\s*,\\s*(?=[,.;])", "").replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([,.;])", "$1").strip();
    }
}
