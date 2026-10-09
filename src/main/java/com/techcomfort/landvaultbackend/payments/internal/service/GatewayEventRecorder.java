package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentGatewayEvent;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentGatewayEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PY-10: stores a signed webhook in its OWN transaction (decided with the
 * user), committed before anything acts on it — so if processing then fails,
 * the record of what Paystack sent survives, and Paystack's retry is handled
 * against it. A separate bean, because REQUIRES_NEW only takes effect through
 * Spring's proxy, never on a call within the same class.
 */
@Service
@RequiredArgsConstructor
public class GatewayEventRecorder {

    private final PaymentGatewayEventRepository events;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String provider, String eventType, String reference, String rawBody) {
        events.save(PaymentGatewayEvent.builder()
                .provider(provider)
                .eventType(eventType)
                .reference(reference)
                .rawBody(rawBody)
                .build());
    }
}
