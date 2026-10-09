package com.techcomfort.landvaultbackend.payments.internal.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TR-2's warning: only a real difference in name is flagged, never punctuation or "LTD" vs "LIMITED". */
class SettlementAccountNamesTest {

    @Test
    void companyFormWordsCaseAndPunctuationDoNotCount() {
        assertThat(SettlementAccountNames.matchesCompany("ESTINTIN GROUP LIMITED", "Estintin Group Ltd.", null)).isTrue();
        assertThat(SettlementAccountNames.matchesCompany("TOP RANK & CO PLC", "Top Rank and Co.", null)).isTrue();
    }

    @Test
    void theTradingNameCountsToo() {
        assertThat(SettlementAccountNames.matchesCompany("DOUBLE KING HOMES", "DK Holdings Ltd", "Double King Homes"))
                .isTrue();
    }

    @Test
    void aDifferentNameIsFlagged() {
        assertThat(SettlementAccountNames.matchesCompany("JOHN ADEBAYO", "Estintin Group Ltd", "Estintin")).isFalse();
    }

    @Test
    void noBankNameIsNeverAMatch() {
        assertThat(SettlementAccountNames.matchesCompany(null, "Estintin Group Ltd", null)).isFalse();
        assertThat(SettlementAccountNames.matchesCompany("LIMITED", "Ltd", null)).isFalse();
    }
}
