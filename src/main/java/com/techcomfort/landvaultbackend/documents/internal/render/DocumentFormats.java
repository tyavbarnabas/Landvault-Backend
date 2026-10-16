package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** How money, dates and payment methods read on a document. Nigerian time, English month names. */
public final class DocumentFormats {

    public static final ZoneId NIGERIA = ZoneId.of("Africa/Lagos");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a", Locale.UK);

    private DocumentFormats() {
    }

    public static String money(BigDecimal amount, Currency currency) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.UK));
        String symbol = switch (currency) {
            case NGN -> "₦";
            case USD -> "$";
            case GBP -> "£";
            case EUR -> "€";
        };
        return symbol + format.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    public static String date(Instant instant) {
        return instant == null ? "—" : DATE.format(instant.atZone(NIGERIA));
    }

    /** "16 October 2026, 7:40 pm (WAT)". */
    public static String dateTime(Instant instant) {
        return instant == null ? "—"
                : DATE_TIME.format(instant.atZone(NIGERIA)).replace("AM", "am").replace("PM", "pm") + " (WAT)";
    }

    /** Paystack's channel in words; the last 4 digits are a card's, or the paying account's for a transfer. */
    public static String paymentMethod(String channel, String last4) {
        String ending = last4 == null || last4.isBlank() ? "" : " ending " + last4;
        if (channel == null || channel.isBlank()) {
            return "Paystack";
        }
        String method = switch (channel) {
            case "card" -> "Card" + ending;
            case "bank_transfer" -> "Bank transfer" + (ending.isEmpty() ? "" : ", account" + ending);
            case "bank" -> "Bank account" + ending;
            case "ussd" -> "USSD";
            default -> Character.toUpperCase(channel.charAt(0)) + channel.substring(1).replace('_', ' ') + ending;
        };
        return method + " (via Paystack)";
    }

    public static String size(BigDecimal sqm) {
        return sqm == null ? null : sqm.stripTrailingZeros().toPlainString() + " sqm";
    }
}
