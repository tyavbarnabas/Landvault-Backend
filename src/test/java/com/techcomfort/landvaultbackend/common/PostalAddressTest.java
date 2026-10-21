package com.techcomfort.landvaultbackend.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostalAddressTest {

    @Test
    void aNigerianAddressPrintsStreetTownAndStateThenTheCountry() {
        PostalAddress address = new PostalAddress("12 Adeola Odeku Street", "Victoria Island", "Ikeja", "Lagos",
                null, "NG");

        assertThat(address.printedLines())
                .containsExactly("12 Adeola Odeku Street, Victoria Island", "Ikeja, Lagos", "Nigeria");
    }

    @Test
    void aTownNamedLikeItsStatePrintsOnce() {
        assertThat(new PostalAddress("1 Marina", null, "Lagos", "Lagos", null, "NG").printedLines())
                .containsExactly("1 Marina", "Lagos", "Nigeria");
    }

    @Test
    void aPostalCodeFollowsTheTownAbroad() {
        assertThat(new PostalAddress("10 Downing Street", null, "London", "Greater London", "SW1A 2AA", "GB")
                .printedLines())
                .containsExactly("10 Downing Street", "London, Greater London SW1A 2AA", "United Kingdom");
    }
}
