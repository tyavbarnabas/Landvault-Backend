package com.techcomfort.landvaultbackend.tenancy.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** <strong>Tests and CI only</strong>, so the suite needs no mail server. Neither email carries a credential. */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.onboarding.delivery", havingValue = "log")
public class LoggingOnboardingRequestMailer implements OnboardingRequestMailer {

    @Override
    public void requestReceived(RequestReceivedAlert alert) {
        log.info("[TEST-ONLY onboarding request alert] to={} company={}", alert.to(), alert.companyName());
    }

    @Override
    public void confirmation(String to) {
        log.info("[TEST-ONLY onboarding request confirmation] to={}", to);
    }
}
