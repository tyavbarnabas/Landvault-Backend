package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.internal.enums.OtpChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Writes the code to the application log instead of sending it anywhere.
 * <p>
 * <strong>For tests and CI only.</strong> {@link EmailOtpDeliveryService} is
 * the default; this exists so the suite can assert a code was generated
 * without requiring a MailDev container to be running, which would be
 * needless coupling. Selected by {@code landvault.otp.delivery=log}, set in
 * {@code src/test/resources/application.properties}.
 * <p>
 * <strong>Never select this in a real environment.</strong> It is the one
 * place in this codebase permitted to log a code; every other path — this
 * project's own {@code EmailOtpDeliveryService} included — treats a code the
 * way {@code SuperAdminBootstrap} treats a password, which is to never log it
 * at any level, including debug. Enabling it outside tests would write live
 * reset codes into the application log, where anyone with log access could
 * take over an account.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.otp.delivery", havingValue = "log")
public class LoggingOtpDeliveryService implements OtpDeliveryService {

    @Override
    public void send(String destination, String code, OtpChannel channel) {
        log.info("TEST-ONLY OTP DELIVERY — channel={} destination={} code={} "
                        + "(never enable this outside tests: it puts live reset codes in the log)",
                channel, destination, code);
    }
}
