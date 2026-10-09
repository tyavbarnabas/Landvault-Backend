package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackProperties;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * PY-7, PY-10, PY-4: a Paystack webhook. Signature first, over the raw bytes;
 * then stored exactly as received; then acted on. A {@code charge.success} is
 * a prompt to confirm, never proof: it runs the same {@code confirm} as the
 * return page, which asks Paystack's verify API itself. A {@code transfer.*}
 * event likewise prompts a payout check (TR-3). Processed in the
 * request (decided with the user); a failure after storing surfaces as an
 * error so Paystack retries. See AGENTS.md, "Payments".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackWebhookService {

    private static final String PROVIDER = "PAYSTACK";
    /** No user: Paystack prompted this, the system acted. */
    private static final TenantScope PLATFORM_SCOPE = new TenantScope(null, null, null, true);

    private final PaystackProperties properties;
    private final GatewayEventRecorder recorder;
    private final PaymentConfirmationService confirmation;
    private final PayoutOutcomeService payoutOutcomes;
    private final JsonMapper json;

    public void handle(byte[] rawBody, String signature) {
        if (!PaystackSignature.isValid(rawBody, signature, properties.secretKey())) {
            // Logged, not stored (decided with the user). Never the body or the presented signature.
            log.warn("Rejected a Paystack webhook with a {} signature", signature == null || signature.isBlank()
                    ? "missing" : "non-matching");
            throw new PaymentException.WebhookSignatureInvalid();
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode event = parse(body);
        String eventType = text(event, "event");
        String reference = text(event.path("data"), "reference");
        recorder.record(PROVIDER, eventType, reference, body);

        if ("charge.success".equals(eventType) && reference != null) {
            confirmation.confirmFromGateway(reference);
        }
        if (eventType != null && eventType.startsWith("transfer.") && reference != null) {
            refreshPayout(reference);
        }
        // Everything else is kept and otherwise ignored.
    }

    /**
     * TR-3. {@code payouts} is platform-scope only under row-level security and
     * a webhook arrives with no scope at all, so the platform scope is set for
     * exactly this call — after the signature check, and only to run the
     * verify-then-record refresh, which asks Paystack itself and never trusts
     * the message. Set before the refresh's transaction begins; cleared after.
     */
    private void refreshPayout(String reference) {
        Optional<TenantScope> previous = TenantContext.get();
        TenantContext.set(PLATFORM_SCOPE);
        try {
            payoutOutcomes.refresh(reference);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
