package com.techcomfort.landvaultbackend.payments.internal.paystack;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PY-8: naira ↔ kobo, exactly. Known answers, including Paystack's own example (40333 = ₦403.33). */
class PaystackAmountsTest {

    @Test
    void nairaBecomesWholeKobo() {
        assertThat(PaystackAmounts.toKobo(new BigDecimal("1.00"), Currency.NGN)).isEqualTo(100L);
        assertThat(PaystackAmounts.toKobo(new BigDecimal("403.33"), Currency.NGN)).isEqualTo(40333L);
        assertThat(PaystackAmounts.toKobo(new BigDecimal("4500000.00"), Currency.NGN)).isEqualTo(450000000L);
        assertThat(PaystackAmounts.toKobo(new BigDecimal("22500001.1300"), Currency.NGN))
                .as("trailing zeros from a 4-decimal column are fine").isEqualTo(2250000113L);
    }

    @Test
    void koboBecomesNairaWithTwoDecimals() {
        assertThat(PaystackAmounts.fromKobo(40333L)).isEqualByComparingTo("403.33").hasScaleOf(2);
        assertThat(PaystackAmounts.fromKobo(450000000L)).isEqualTo(new BigDecimal("4500000.00"));
    }

    @Test
    void theRoundTripIsExact() {
        BigDecimal agreed = new BigDecimal("12610000.00");
        assertThat(PaystackAmounts.fromKobo(PaystackAmounts.toKobo(agreed, Currency.NGN))).isEqualByComparingTo(agreed);
    }

    @Test
    void whatCannotBeChargedIsRefusedNotRounded() {
        assertThatThrownBy(() -> PaystackAmounts.toKobo(new BigDecimal("1388888.8889"), Currency.NGN))
                .as("rounding happens at reservation, never here").isInstanceOf(PaymentException.InvalidAmount.class);
        assertThatThrownBy(() -> PaystackAmounts.toKobo(BigDecimal.ZERO, Currency.NGN))
                .isInstanceOf(PaymentException.InvalidAmount.class);
        assertThatThrownBy(() -> PaystackAmounts.toKobo(new BigDecimal("-5.00"), Currency.NGN))
                .isInstanceOf(PaymentException.InvalidAmount.class);
        assertThatThrownBy(() -> PaystackAmounts.toKobo(new BigDecimal("40000.00"), Currency.USD))
                .isInstanceOf(PaymentException.UnsupportedCurrency.class).hasMessageContaining("USD");
    }
}
