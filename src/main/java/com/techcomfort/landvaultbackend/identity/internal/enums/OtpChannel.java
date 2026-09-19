package com.techcomfort.landvaultbackend.identity.internal.enums;

/**
 * How an OTP was delivered, recorded on the code row at send time.
 * <p>
 * Only {@link #EMAIL} is produced today — {@code LoggingOtpDeliveryService}
 * is the sole implementation and password reset always sends to the user's
 * email. The other two constants exist so adding an SMS/WhatsApp
 * implementation needs no schema change, not because anything emits them.
 */
public enum OtpChannel {

    EMAIL,
    SMS,
    WHATSAPP
}
