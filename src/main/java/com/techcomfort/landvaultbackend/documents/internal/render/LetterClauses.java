package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An allocation letter's numbered conditions, in order: the template's standard
 * ones, the estate's own, then HomeTrue's disclosure clauses. A condition
 * repeated word for word (ignoring case, spacing and a final full stop) prints
 * once, where it first appears. Used by the preview and the issued letter alike.
 */
public final class LetterClauses {

    private LetterClauses() {
    }

    public static List<String> of(List<String> standard, List<String> estate, EstateDocumentFacts facts) {
        Map<String, String> unique = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(standard);
        all.addAll(estate);
        all.addAll(DisclosureClauses.of(facts));
        all.forEach(clause -> unique.putIfAbsent(key(clause), clause));
        return List.copyOf(unique.values());
    }

    static String key(String clause) {
        String key = clause.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return key.endsWith(".") ? key.substring(0, key.length() - 1) : key;
    }
}
