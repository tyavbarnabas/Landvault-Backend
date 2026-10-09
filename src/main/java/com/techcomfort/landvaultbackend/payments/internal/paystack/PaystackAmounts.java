package com.techcomfort.landvaultbackend.payments.internal.paystack;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;

import java.math.BigDecimal;

/**
 * The one place naira becomes kobo and back (PY-8). The app keeps money as
 * exact {@code BigDecimal} naira; Paystack takes and reports whole kobo
 * (₦1 = 100 kobo, so {@code 40333} is ₦403.33). Convert only here.
 * <p>
 * Exact, never rounding: an agreed price is rounded to the kobo once, when
 * the plot is reserved, so a fraction of a kobo reaching this point is a bug
 * upstream and is refused rather than quietly rounded again.
 */
public final class PaystackAmounts {

    private static final int KOBO_PER_NAIRA_SCALE = 2;

    private PaystackAmounts() {
    }

    /** Naira to kobo, for asking Paystack to charge. NGN only, above zero, whole kobo. */
    public static long toKobo(BigDecimal amount, Currency currency) {
        if (currency != Currency.NGN) {
            throw new PaymentException.UnsupportedCurrency(String.valueOf(currency));
        }
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentException.InvalidAmount("A payment must be for more than ₦0.");
        }
        if (amount.stripTrailingZeros().scale() > KOBO_PER_NAIRA_SCALE) {
            throw new PaymentException.InvalidAmount("₦" + amount.toPlainString()
                    + " includes a fraction of a kobo, which can't be charged.");
        }
        // Exact: throws rather than truncating if it ever didn't fit.
        return amount.movePointRight(KOBO_PER_NAIRA_SCALE).longValueExact();
    }

    /** Kobo to naira, for reading what Paystack reports. Always two decimal places. */
    public static BigDecimal fromKobo(long kobo) {
        return BigDecimal.valueOf(kobo, KOBO_PER_NAIRA_SCALE);
    }
}
