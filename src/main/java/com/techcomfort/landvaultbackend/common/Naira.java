package com.techcomfort.landvaultbackend.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** An amount as a person reads it: ₦6,500,000.00. */
public final class Naira {

    private Naira() {
    }

    public static String format(BigDecimal amount) {
        return "₦" + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.UK))
                .format(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
