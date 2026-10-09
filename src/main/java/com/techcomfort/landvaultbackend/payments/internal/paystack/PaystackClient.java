package com.techcomfort.landvaultbackend.payments.internal.paystack;

import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Paystack calls payments and payouts need (PY-1, PY-3, TR-1, TR-2). Thin on purpose: it
 * sends and reads, and decides nothing about our transactions.
 * <p>
 * Paystack's top-level {@code status} only says the API call worked; whether
 * money arrived is {@code data.status}. They are kept apart here so no caller
 * can mistake one for the other.
 */
@Component
public class PaystackClient {

    private final RestClient http;
    private final JsonMapper json;

    public PaystackClient(@Qualifier("paystackRestClient") RestClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    /** What we ask Paystack to charge. {@code amountKobo} comes from {@link PaystackAmounts}. */
    public record InitializeRequest(String email, long amountKobo, String reference, String callbackUrl,
                                    Map<String, Object> metadata) {
    }

    /** Where to send the buyer to pay. */
    public record InitializeResult(String authorizationUrl, String accessCode, String reference) {
    }

    /**
     * Paystack's verdict on one payment. {@code paymentStatus} is
     * {@code data.status} (success, failed, abandoned, ...). {@code rawBody} is
     * the reply exactly as received, kept for disputes (PY-10). Only
     * {@code last4} and {@code bin} are ever read — of a card, or of the
     * paying account for a bank transfer.
     */
    public record Verification(boolean found, String paymentStatus, String reference, long amountKobo,
                               String currency, String gatewayResponse, Instant paidAt, String channel,
                               String last4, String cardBin, String rawBody) {

        public boolean succeeded() {
            return found && "success".equals(paymentStatus);
        }
    }

    public InitializeResult initialize(InitializeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", request.email());
        body.put("amount", request.amountKobo());
        body.put("currency", "NGN");
        body.put("reference", request.reference());
        if (request.callbackUrl() != null) {
            body.put("callback_url", request.callbackUrl());
        }
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            body.put("metadata", request.metadata());
        }
        JsonNode reply = call(() -> http.post().uri("/transaction/initialize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        JsonNode data = reply.path("data");
        return new InitializeResult(data.path("authorization_url").asString(), data.path("access_code").asString(),
                data.path("reference").asString());
    }

    /** Asks Paystack what happened to a payment. An unknown reference is {@code found = false}, not an error. */
    public Verification verify(String reference) {
        String raw;
        try {
            raw = http.get().uri("/transaction/verify/{reference}", reference).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                return new Verification(false, null, reference, 0, null, null, null, null, null, null,
                        e.getResponseBodyAsString());
            }
            throw unavailable(e);
        } catch (ResourceAccessException e) {
            throw new PaymentException.GatewayUnavailable();
        }
        JsonNode data = parse(raw).path("data");
        JsonNode card = data.path("authorization");
        String paidAt = text(data, "paid_at");
        return new Verification(true, text(data, "status"), text(data, "reference"), data.path("amount").asLong(),
                text(data, "currency"), text(data, "gateway_response"),
                paidAt == null ? null : Instant.parse(paidAt), text(data, "channel"),
                text(card, "last4"), text(card, "bin"), raw);
    }

    /**
     * One Nigerian bank Paystack can pay to. {@code code} is what most calls
     * need; {@code id} is Paystack's own number for it, which the refund retry
     * asks for instead.
     */
    public record Bank(String code, String name, String id) {

        public Bank(String code, String name) {
            this(code, name, null);
        }
    }

    /** Most banks per page Paystack allows; the list is followed page by page. */
    private static final int BANKS_PER_PAGE = 100;
    /** A guard against a cursor that never ends — Nigeria has a few hundred banks, not thousands. */
    private static final int MAX_BANK_PAGES = 20;

    /**
     * TR-1: every active NGN bank that takes NUBAN transfers. Follows
     * Paystack's cursor until the last page.
     */
    public List<Bank> listBanks() {
        List<Bank> banks = new ArrayList<>();
        String next = null;
        for (int page = 0; page < MAX_BANK_PAGES; page++) {
            String cursor = next;
            JsonNode reply = call(() -> http.get().uri(uri -> {
                uri.path("/bank").queryParam("country", "nigeria").queryParam("currency", "NGN")
                        .queryParam("use_cursor", true).queryParam("perPage", BANKS_PER_PAGE);
                if (cursor != null) {
                    uri.queryParam("next", cursor);
                }
                return uri.build();
            }).retrieve().body(String.class));
            for (JsonNode bank : reply.path("data")) {
                boolean usable = bank.path("active").asBoolean(false)
                        && !bank.path("is_deleted").asBoolean(false)
                        && "nuban".equals(text(bank, "type"));
                if (usable && text(bank, "code") != null) {
                    banks.add(new Bank(text(bank, "code"), text(bank, "name"), text(bank, "id")));
                }
            }
            next = text(reply.path("meta"), "next");
            if (next == null || next.isBlank()) {
                break;
            }
        }
        return banks;
    }

    /**
     * TR-2: the name the bank holds for this account. Empty when the bank
     * can't resolve it (a wrong number, or the wrong bank) — that is the
     * person's mistake to fix, not a gateway failure.
     */
    public Optional<String> resolveAccountName(String accountNumber, String bankCode) {
        String raw;
        try {
            raw = http.get().uri(uri -> uri.path("/bank/resolve").queryParam("account_number", accountNumber)
                    .queryParam("bank_code", bankCode).build()).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                return Optional.empty();
            }
            throw unavailable(e);
        } catch (ResourceAccessException e) {
            throw new PaymentException.GatewayUnavailable();
        }
        JsonNode reply = parse(raw);
        if (!reply.path("status").asBoolean(false)) {
            return Optional.empty();
        }
        return Optional.ofNullable(text(reply.path("data"), "account_name"));
    }

    /**
     * TR-1: registers an account with Paystack. Transfers go to the returned
     * {@code RCP_…} code, never to an account number.
     */
    public String createRecipient(String accountName, String accountNumber, String bankCode, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "nuban");
        body.put("name", accountName);
        body.put("account_number", accountNumber);
        body.put("bank_code", bankCode);
        body.put("currency", "NGN");
        if (description != null) {
            body.put("description", description);
        }
        JsonNode reply = call(() -> http.post().uri("/transferrecipient")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        String code = text(reply.path("data"), "recipient_code");
        if (code == null || code.isBlank()) {
            throw new PaymentException.GatewayRefused("no recipient code was returned.");
        }
        return code;
    }

    /**
     * Paystack's word on one transfer. {@code status} is {@code data.status}:
     * otp, pending, success, failed, reversed, … {@code message} is Paystack's
     * own explanation where it gave one. {@code found} is false only when
     * Paystack has no transfer with that reference.
     */
    public record Transfer(boolean found, String status, String transferCode, String reference, String message,
                           Instant transferredAt) {
    }

    /**
     * TR-1: asks Paystack to send money from the balance to a recipient.
     * {@code reference} is ours — Paystack refuses a reference it has seen,
     * which is what makes resending after a lost reply safe.
     */
    public Transfer initiateTransfer(long amountKobo, String recipientCode, String reference, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "balance");
        body.put("amount", amountKobo);
        body.put("currency", "NGN");
        body.put("recipient", recipientCode);
        body.put("reference", reference);
        if (reason != null) {
            body.put("reason", reason);
        }
        JsonNode reply = call(() -> http.post().uri("/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        return transfer(reply);
    }

    /** Sends the OTP Paystack texted the account owner. The code is passed straight through, never kept. */
    public Transfer finalizeTransfer(String transferCode, String otp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transfer_code", transferCode);
        body.put("otp", otp);
        JsonNode reply = call(() -> http.post().uri("/transfer/finalize_transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        return transfer(reply);
    }

    /**
     * The reason must be {@code transfer}: Paystack's OpenAPI lists
     * {@code resend_otp} too, but the live API refuses it ("Reason is invalid.
     * ['disable_otp' or 'transfer']") — found in the first live test.
     */
    public void resendTransferOtp(String transferCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transfer_code", transferCode);
        body.put("reason", "transfer");
        call(() -> http.post().uri("/transfer/resend_otp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
    }

    /** What Paystack holds for our reference. Unknown reference is {@code found = false}, not an error. */
    public Transfer verifyTransfer(String reference) {
        String raw;
        try {
            raw = http.get().uri("/transfer/verify/{reference}", reference).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                return new Transfer(false, null, null, reference, null, null);
            }
            throw unavailable(e);
        } catch (ResourceAccessException e) {
            throw new PaymentException.GatewayUnavailable();
        }
        JsonNode reply = parse(raw);
        if (!reply.path("status").asBoolean(false)) {
            return new Transfer(false, null, null, reference, text(reply, "message"), null);
        }
        return transfer(reply);
    }

    private static Transfer transfer(JsonNode reply) {
        JsonNode data = reply.path("data");
        return new Transfer(true, text(data, "status"), text(data, "transfer_code"), text(data, "reference"),
                text(reply, "message"), instant(text(data, "transferred_at")));
    }

    /**
     * Paystack's word on one refund. {@code status}: pending, processing,
     * needs-attention, processed, failed. {@code id} is Paystack's refund id.
     */
    public record Refund(String id, String status, String message, Instant refundedAt, Instant expectedAt) {
    }

    /**
     * Asks Paystack to return a payment's money (by OUR payment reference).
     * Paystack itself refuses to refund more than was paid.
     */
    public Refund createRefund(String paymentReference, long amountKobo, String customerNote, String merchantNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction", paymentReference);
        body.put("amount", amountKobo);
        body.put("currency", "NGN");
        if (customerNote != null) {
            body.put("customer_note", customerNote);
        }
        if (merchantNote != null) {
            body.put("merchant_note", merchantNote);
        }
        JsonNode reply = call(() -> http.post().uri("/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        return refund(reply);
    }

    public Refund fetchRefund(String refundId) {
        JsonNode reply = call(() -> http.get().uri("/refund/{id}", refundId).retrieve().body(String.class));
        return refund(reply);
    }

    /**
     * A refund Paystack couldn't return on its own ({@code needs-attention}):
     * sends it to the account the buyer gave. {@code bankId} is Paystack's bank
     * id, not the bank code.
     */
    public Refund retryRefundWithAccount(String refundId, String accountNumber, String bankId) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("currency", "NGN");
        account.put("account_number", accountNumber);
        account.put("bank_id", bankId);
        JsonNode reply = call(() -> http.post().uri("/refund/retry_with_customer_details/{id}", refundId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refund_account_details", account))
                .retrieve()
                .body(String.class));
        return refund(reply);
    }

    private static Refund refund(JsonNode reply) {
        JsonNode data = reply.path("data");
        return new Refund(text(data, "id"), text(data, "status"), text(reply, "message"),
                instant(text(data, "refunded_at")), instant(text(data, "expected_at")));
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- plumbing ---

    private JsonNode call(Supplier<String> request) {
        try {
            JsonNode reply = parse(request.get());
            if (!reply.path("status").asBoolean(false)) {
                throw new PaymentException.GatewayRefused(reply.path("message").asString("Paystack refused the request."));
            }
            return reply;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new PaymentException.GatewayRefused(parse(e.getResponseBodyAsString())
                        .path("message").asString("Paystack refused the request."));
            }
            throw unavailable(e);
        } catch (ResourceAccessException e) {
            throw new PaymentException.GatewayUnavailable();
        }
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null ? "{}" : body);
        } catch (RuntimeException e) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static PaymentException unavailable(RestClientResponseException e) {
        return new PaymentException.GatewayUnavailable();
    }
}
