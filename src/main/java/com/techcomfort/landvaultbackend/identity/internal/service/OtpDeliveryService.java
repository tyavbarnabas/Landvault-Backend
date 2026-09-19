package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.internal.enums.OtpChannel;

/**
 * Delivers a one-time code to its destination. The one seam between this
 * flow and the outside world — choosing a real provider is a separate
 * decision that slots in behind this interface without touching any reset
 * logic.
 * <p>
 * Implementations must never persist or return the raw code; they receive it
 * only to send it.
 */
public interface OtpDeliveryService {

    void send(String destination, String code, OtpChannel channel);
}
