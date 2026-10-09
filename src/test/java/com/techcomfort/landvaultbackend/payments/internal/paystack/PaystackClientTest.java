package com.techcomfort.landvaultbackend.payments.internal.paystack;

import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Paystack client against a stand-in Paystack: what we send, and how we
 * read the reply. Response bodies follow Paystack's published API shapes.
 */
class PaystackClientTest {

    private static final String KEY = "sk_test_unit_test_key";

    private MockRestServiceServer paystack;
    private PaystackClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.paystack.co")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + KEY);
        paystack = MockRestServiceServer.bindTo(builder).build();
        client = new PaystackClient(builder.build(), JsonMapper.builder().build());
    }

    @Test
    void initializeSendsKoboOurReferenceAndMetadataAndReturnsThePaymentLink() {
        paystack.expect(requestTo("https://api.paystack.co/transaction/initialize"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value(2250000113L))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.email").value("buyer@example.com"))
                .andExpect(jsonPath("$.reference").value("LV-123"))
                .andExpect(jsonPath("$.callback_url").value("http://localhost:8443/payments/return"))
                .andExpect(jsonPath("$.metadata.transactionId").value("tx-1"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Authorization URL created",
                         "data":{"authorization_url":"https://checkout.paystack.com/0peioxfhpn",
                                 "access_code":"0peioxfhpn","reference":"LV-123"}}""", MediaType.APPLICATION_JSON));

        PaystackClient.InitializeResult result = client.initialize(new PaystackClient.InitializeRequest(
                "buyer@example.com", 2250000113L, "LV-123", "http://localhost:8443/payments/return",
                Map.of("transactionId", "tx-1")));

        assertThat(result.authorizationUrl()).isEqualTo("https://checkout.paystack.com/0peioxfhpn");
        assertThat(result.reference()).isEqualTo("LV-123");
        paystack.verify();
    }

    @Test
    void aRefusalCarriesPaystacksOwnMessage() {
        paystack.expect(requestTo("https://api.paystack.co/transaction/initialize"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Duplicate Transaction Reference\"}"));

        assertThatThrownBy(() -> client.initialize(new PaystackClient.InitializeRequest(
                "buyer@example.com", 100, "LV-dup", null, null)))
                .isInstanceOf(PaymentException.GatewayRefused.class)
                .hasMessageContaining("Duplicate Transaction Reference")
                .as("the key never appears in an error").hasMessageNotContaining(KEY);
    }

    @Test
    void paystackBeingDownIsReportedAsUnavailableNotRefused() {
        paystack.expect(requestTo("https://api.paystack.co/transaction/initialize"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.initialize(new PaystackClient.InitializeRequest(
                "buyer@example.com", 100, "LV-x", null, null)))
                .isInstanceOf(PaymentException.GatewayUnavailable.class);
    }

    /** The trap Paystack names: the call succeeding (status: true) is not the payment succeeding. */
    @Test
    void verifyReadsTheMoneyStatusNotTheApiStatus() {
        paystack.expect(requestTo("https://api.paystack.co/transaction/verify/LV-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Verification successful",
                         "data":{"status":"failed","reference":"LV-123","amount":2250000113,"currency":"NGN",
                                 "gateway_response":"Insufficient Funds","paid_at":null,"channel":"card",
                                 "authorization":{"last4":"4081","bin":"408408","authorization_code":"AUTH_secret"}}}""",
                        MediaType.APPLICATION_JSON));

        PaystackClient.Verification v = client.verify("LV-123");

        assertThat(v.found()).isTrue();
        assertThat(v.succeeded()).as("status:true at the top, but the payment failed").isFalse();
        assertThat(v.paymentStatus()).isEqualTo("failed");
        assertThat(v.gatewayResponse()).isEqualTo("Insufficient Funds");
        assertThat(v.last4()).isEqualTo("4081");
        assertThat(v.cardBin()).isEqualTo("408408");
        assertThat(v.rawBody()).as("kept exactly as received").contains("\"gateway_response\":\"Insufficient Funds\"");
    }

    @Test
    void aSuccessfulPaymentIsReadWithItsAmountAndTime() {
        paystack.expect(requestTo("https://api.paystack.co/transaction/verify/LV-9"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Verification successful",
                         "data":{"status":"success","reference":"LV-9","amount":450000000,"currency":"NGN",
                                 "gateway_response":"Approved","paid_at":"2026-10-07T13:45:57.000Z","channel":"card",
                                 "authorization":{"last4":"4081","bin":"408408"}}}""", MediaType.APPLICATION_JSON));

        PaystackClient.Verification v = client.verify("LV-9");

        assertThat(v.succeeded()).isTrue();
        assertThat(PaystackAmounts.fromKobo(v.amountKobo())).isEqualByComparingTo("4500000.00");
        assertThat(v.paidAt()).isEqualTo(Instant.parse("2026-10-07T13:45:57Z"));
    }

    @Test
    void anUnknownReferenceIsNotFoundRatherThanAnError() {
        paystack.expect(requestTo("https://api.paystack.co/transaction/verify/nope"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Transaction reference not found\"}"));

        PaystackClient.Verification v = client.verify("nope");

        assertThat(v.found()).isFalse();
        assertThat(v.succeeded()).isFalse();
    }

    @Test
    void theBankListFollowsPaystacksCursorAndKeepsOnlyActiveNubanBanks() {
        paystack.expect(requestTo("https://api.paystack.co/bank?country=nigeria&currency=NGN&use_cursor=true&perPage=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Banks retrieved","data":[
                          {"name":"Guaranty Trust Bank","code":"058","active":true,"is_deleted":false,"type":"nuban"},
                          {"name":"Closed Bank","code":"999","active":false,"is_deleted":false,"type":"nuban"},
                          {"name":"Mobile Money Co","code":"MM1","active":true,"is_deleted":false,"type":"mobile_money"}],
                         "meta":{"next":"YmFuazoxNjk=","previous":null,"perPage":100}}""", MediaType.APPLICATION_JSON));
        paystack.expect(requestTo("https://api.paystack.co/bank?country=nigeria&currency=NGN&use_cursor=true&perPage=100&next=YmFuazoxNjk%3D"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Banks retrieved","data":[
                          {"name":"Access Bank","code":"044","active":true,"is_deleted":false,"type":"nuban"}],
                         "meta":{"next":null,"previous":"YmFuazoxNjk=","perPage":100}}""", MediaType.APPLICATION_JSON));

        List<PaystackClient.Bank> banks = client.listBanks();

        assertThat(banks).containsExactly(new PaystackClient.Bank("058", "Guaranty Trust Bank"),
                new PaystackClient.Bank("044", "Access Bank"));
        paystack.verify();
    }

    @Test
    void resolvingAnAccountReturnsTheNameTheBankHolds() {
        paystack.expect(requestTo("https://api.paystack.co/bank/resolve?account_number=0123456789&bank_code=058"))
                .andExpect(queryParam("account_number", "0123456789"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Account number resolved",
                         "data":{"account_number":"0123456789","account_name":"ESTINTIN GROUP LIMITED","bank_id":9}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveAccountName("0123456789", "058")).contains("ESTINTIN GROUP LIMITED");
    }

    @Test
    void anAccountTheBankCantFindIsEmptyNotAGatewayFailure() {
        paystack.expect(requestTo("https://api.paystack.co/bank/resolve?account_number=0000000000&bank_code=058"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Could not resolve account name. Check parameters or try again.\"}"));

        assertThat(client.resolveAccountName("0000000000", "058")).isEqualTo(Optional.empty());
    }

    @Test
    void creatingARecipientSendsANubanAccountAndReturnsItsCode() {
        paystack.expect(requestTo("https://api.paystack.co/transferrecipient"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.type").value("nuban"))
                .andExpect(jsonPath("$.account_number").value("0123456789"))
                .andExpect(jsonPath("$.bank_code").value("058"))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.name").value("ESTINTIN GROUP LIMITED"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Transfer recipient created successfully",
                         "data":{"active":true,"currency":"NGN","name":"ESTINTIN GROUP LIMITED",
                                 "recipient_code":"RCP_t0ya41mp35flk40","type":"nuban"}}""", MediaType.APPLICATION_JSON));

        assertThat(client.createRecipient("ESTINTIN GROUP LIMITED", "0123456789", "058", "LandVault payouts"))
                .isEqualTo("RCP_t0ya41mp35flk40");
        paystack.verify();
    }

    @Test
    void aRecipientPaystackDeclinesIsARefusal() {
        paystack.expect(requestTo("https://api.paystack.co/transferrecipient"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Account number is invalid\"}"));

        assertThatThrownBy(() -> client.createRecipient("X", "0123456789", "058", null))
                .isInstanceOf(PaymentException.GatewayRefused.class)
                .hasMessageContaining("Account number is invalid");
    }

    @Test
    void aTransferIsSentFromTheBalanceInKoboWithOurReferenceAndReportsTheOtpStep() {
        paystack.expect(requestTo("https://api.paystack.co/transfer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.source").value("balance"))
                .andExpect(jsonPath("$.amount").value(1500000000L))
                .andExpect(jsonPath("$.recipient").value("RCP_t0ya41mp35flk40"))
                .andExpect(jsonPath("$.reference").value("lv-payout-abc123def4567890"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Transfer requires OTP to continue",
                         "data":{"reference":"lv-payout-abc123def4567890","amount":1500000000,
                                 "status":"otp","transfer_code":"TRF_1ptvuv321ahaa7q"}}""", MediaType.APPLICATION_JSON));

        PaystackClient.Transfer transfer = client.initiateTransfer(1500000000L, "RCP_t0ya41mp35flk40",
                "lv-payout-abc123def4567890", "LandVault payout");

        assertThat(transfer.status()).isEqualTo("otp");
        assertThat(transfer.transferCode()).isEqualTo("TRF_1ptvuv321ahaa7q");
        assertThat(transfer.message()).isEqualTo("Transfer requires OTP to continue");
        paystack.verify();
    }

    @Test
    void theOtpIsSentWithTheTransferCode() {
        paystack.expect(requestTo("https://api.paystack.co/transfer/finalize_transfer"))
                .andExpect(jsonPath("$.transfer_code").value("TRF_1ptvuv321ahaa7q"))
                .andExpect(jsonPath("$.otp").value("928783"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Transfer has been queued",
                         "data":{"status":"pending","transfer_code":"TRF_1ptvuv321ahaa7q",
                                 "reference":"lv-payout-abc123def4567890"}}""", MediaType.APPLICATION_JSON));

        assertThat(client.finalizeTransfer("TRF_1ptvuv321ahaa7q", "928783").status()).isEqualTo("pending");
        paystack.verify();
    }

    @Test
    void aTransferPaystackHasNeverSeenIsNotFoundRatherThanAnError() {
        paystack.expect(requestTo("https://api.paystack.co/transfer/verify/lv-payout-unknown0000000000"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Transfer not found\"}"));

        assertThat(client.verifyTransfer("lv-payout-unknown0000000000").found()).isFalse();
    }

    @Test
    void resendingTheOtpUsesTheReasonTheLiveApiAccepts() {
        paystack.expect(requestTo("https://api.paystack.co/transfer/resend_otp"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transfer_code").value("TRF_1ptvuv321ahaa7q"))
                .andExpect(jsonPath("$.reason").value("transfer"))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"OTP has been resent\"}",
                        MediaType.APPLICATION_JSON));

        client.resendTransferOtp("TRF_1ptvuv321ahaa7q");

        paystack.verify();
    }
}
