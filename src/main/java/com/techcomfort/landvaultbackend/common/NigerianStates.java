package com.techcomfort.landvaultbackend.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Standardises a Nigerian state name against the {@code nigerian_states}
 * reference table (changeset 064): "FCT", "Abuja", "Lagos State", an ISO code
 * or the canonical name all resolve to one canonical name and code. In
 * {@code common} because both {@code inventory} (estates, SB-1) and
 * {@code tenancy} (branch offices) need it, and tenancy cannot depend on
 * inventory without a cycle. Reference data, so a shared-kernel lookup rather
 * than domain logic; each caller decides how to refuse an unknown name.
 */
@Component
public class NigerianStates {

    @PersistenceContext
    private EntityManager entityManager;

    public record State(String code, String name) {
    }

    public Optional<State> resolve(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalised = input.trim().replaceAll("(?i)\\s+state\\s*$", "").toLowerCase(Locale.ROOT);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT code, name FROM nigerian_states
                         WHERE lower(name) = :v OR lower(code) = :v OR :v = ANY (aliases)
                        """)
                .setParameter("v", normalised)
                .getResultList();
        return rows.size() == 1
                ? Optional.of(new State((String) rows.getFirst()[0], (String) rows.getFirst()[1]))
                : Optional.empty();
    }

    /** Every canonical name, for an error message that tells the caller what is valid. */
    public List<String> names() {
        @SuppressWarnings("unchecked")
        List<Object> names = entityManager.createNativeQuery("SELECT name FROM nigerian_states ORDER BY name")
                .getResultList();
        return names.stream().map(Object::toString).toList();
    }
}
