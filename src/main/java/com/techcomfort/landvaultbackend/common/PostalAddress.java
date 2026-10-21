package com.techcomfort.landvaultbackend.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A buyer's postal address, as identity stores it and checkout copies it onto
 * a purchase. In {@code common} because identity, checkout and documents all
 * carry it; a value with its printed form, no rules (identity validates).
 *
 * @param line2      optional
 * @param state      for Nigeria, the canonical state name; free text elsewhere
 * @param postalCode optional
 * @param country    ISO 3166-1 alpha-2, upper case
 */
public record PostalAddress(
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country
) {

    /** How it's printed on a letter: street, then town and state, then the country's name. */
    public List<String> printedLines() {
        List<String> lines = new ArrayList<>();
        lines.add(line2 == null ? line1 : line1 + ", " + line2);
        String town = city.equalsIgnoreCase(state) ? city : city + ", " + state;
        lines.add(postalCode == null ? town : town + " " + postalCode);
        lines.add(countryName());
        return lines;
    }

    public String countryName() {
        String name = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
        return name.isBlank() ? country : name;
    }
}
