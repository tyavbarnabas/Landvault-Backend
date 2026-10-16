package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountInWordsTest {

    @Test
    void spellsNairaAsNigerianReceiptsDo() {
        assertThat(AmountInWords.of(new BigDecimal("15000000.00"), Currency.NGN))
                .isEqualTo("Fifteen Million Naira Only");
        assertThat(AmountInWords.of(new BigDecimal("3500000"), Currency.NGN))
                .isEqualTo("Three Million, Five Hundred Thousand Naira Only");
        assertThat(AmountInWords.of(new BigDecimal("22500001.13"), Currency.NGN))
                .isEqualTo("Twenty-Two Million, Five Hundred Thousand, One Naira and Thirteen Kobo Only");
        assertThat(AmountInWords.of(new BigDecimal("10000.0000"), Currency.NGN)).isEqualTo("Ten Thousand Naira Only");
        assertThat(AmountInWords.of(new BigDecimal("0.50"), Currency.NGN)).isEqualTo("Zero Naira and Fifty Kobo Only");
    }

    @Test
    void spellsHundredsTeensAndOtherCurrencies() {
        assertThat(AmountInWords.spell(115)).isEqualTo("One Hundred and Fifteen");
        assertThat(AmountInWords.spell(1_002_000_000L)).isEqualTo("One Billion, Two Million");
        assertThat(AmountInWords.of(new BigDecimal("99.99"), Currency.USD))
                .isEqualTo("Ninety-Nine US Dollars and Ninety-Nine Cents Only");
        assertThatThrownBy(() -> AmountInWords.of(new BigDecimal("-1"), Currency.NGN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
