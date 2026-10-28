package com.techcomfort.landvaultbackend.common;

import java.util.Locale;

/**
 * A plan's name in a sentence, the same on letters and emails. Developers name
 * plans freely: "12-month plan" reads as it is ("the 12-month plan"), but
 * "Pay as you can" needs quoting ("the \"Pay as you can\" plan") or the
 * sentence reads "you have chosen the Pay as you can".
 */
public final class PlanNames {

    private PlanNames() {
    }

    /** "the 12-month plan", "the \"Pay as you can\" plan". */
    public static String phrase(String planName) {
        String name = planName.trim();
        return name.toLowerCase(Locale.ROOT).endsWith("plan") ? "the " + name : "the \"" + name + "\" plan";
    }
}
