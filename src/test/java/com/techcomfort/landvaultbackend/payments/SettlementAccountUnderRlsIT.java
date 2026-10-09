package com.techcomfort.landvaultbackend.payments;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.payments.dto.CompanySettlementDto;
import com.techcomfort.landvaultbackend.payments.dto.SettlementAccountDto;
import com.techcomfort.landvaultbackend.payments.dto.SettlementAccountReviewDto;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.service.SettlementAlertSender;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TR-1/TR-2 (step 8a): a company's payout account, under real row-level
 * security — the app connects as a restricted role, never the superuser.
 * <strong>Do not convert to {@code @ServiceConnection}</strong>: the
 * company-isolation assertions would then pass with or without changeset 074's
 * policies. Paystack is a stand-in; nothing here calls the real gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class SettlementAccountUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_settlement_it";
    private static final String APP_ROLE_PASSWORD = "settlement-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";
    private static final String GTBANK = "058";
    private static final String ACCESS = "044";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        registry.add("spring.liquibase.user", POSTGRES::getUsername);
        registry.add("spring.liquibase.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.parameters.appDbUsername", () -> APP_ROLE);
        registry.add("spring.liquibase.parameters.appDbPassword", () -> APP_ROLE_PASSWORD);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("landvault.bootstrap.super-admin.enabled", () -> "true");
        registry.add("landvault.bootstrap.super-admin.email", () -> ADMIN_EMAIL);
        registry.add("landvault.bootstrap.super-admin.password", () -> PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private PaystackClient paystack;

    @MockitoBean
    private SettlementAlertSender alerts;

    private Company company;

    @BeforeEach
    void setUp() {
        when(paystack.listBanks()).thenReturn(List.of(
                new PaystackClient.Bank(GTBANK, "Guaranty Trust Bank"), new PaystackClient.Bank(ACCESS, "Access Bank")));
        company = company();
    }

    // ------------------------------------------------------------------
    // Submitting
    // ------------------------------------------------------------------

    @Test
    void theAccountNameComesFromTheBankAndNothingReachesPaystackBeforeApproval() {
        bankKnows("0123456789", GTBANK, "SETTLE CO LIMITED");

        // A name sent by the client is simply not part of the request.
        ResponseEntity<SettlementAccountDto> response = submit(company.director(),
                "{\"bankCode\":\"058\",\"accountNumber\":\"0123456789\",\"accountName\":\"Somebody Else\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SettlementAccountDto account = response.getBody();
        assertThat(account.status()).isEqualTo("pending");
        assertThat(account.accountName()).isEqualTo("SETTLE CO LIMITED");
        assertThat(account.bankName()).isEqualTo("Guaranty Trust Bank");
        assertThat(query("SELECT account_name FROM settlement_accounts WHERE id = '" + account.id() + "'"))
                .isEqualTo("SETTLE CO LIMITED");
        verify(paystack, never()).createRecipient(anyString(), anyString(), anyString(), any());
    }

    @Test
    void everyExecutiveDirectorIsAlertedWhenAnAccountIsSubmitted() {
        bankKnows("0123456789", GTBANK, "SETTLE CO LIMITED");

        submit(company.director(), json(GTBANK, "0123456789"));

        ArgumentCaptor<SettlementAlertSender.AccountSubmittedAlert> alert =
                ArgumentCaptor.forClass(SettlementAlertSender.AccountSubmittedAlert.class);
        verify(alerts).accountSubmitted(alert.capture());
        assertThat(alert.getValue().to()).isEqualTo(company.directorEmail());
        assertThat(alert.getValue().accountLast4()).isEqualTo("6789");
        assertThat(alert.getValue().toString()).as("the alert never carries the full number").doesNotContain("0123456789");
    }

    @Test
    void anAccountTheBankCantFindIsRefusedAndNothingIsSaved() {
        when(paystack.resolveAccountName("0000000000", GTBANK)).thenReturn(Optional.empty());

        ResponseEntity<String> response = submitRaw(company.director(), json(GTBANK, "0000000000"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("ACCOUNT_NOT_RESOLVED");
        assertThat(count("SELECT count(*) FROM settlement_accounts WHERE tenant_id = '" + company.id() + "'")).isZero();
    }

    @Test
    void aBankPaystackDoesntListIsRefused() {
        ResponseEntity<String> response = submitRaw(company.director(), json("999", "0123456789"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("UNKNOWN_BANK");
    }

    @Test
    void oneSubmissionWaitsAtATimeAndWithdrawingFreesTheSlot() {
        bankKnows("0123456789", GTBANK, "SETTLE CO LIMITED");
        bankKnows("9876543210", ACCESS, "SETTLE CO LIMITED");
        SettlementAccountDto first = submit(company.director(), json(GTBANK, "0123456789")).getBody();

        ResponseEntity<String> second = submitRaw(company.director(), json(ACCESS, "9876543210"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("SETTLEMENT_ACCOUNT_PENDING");

        ResponseEntity<SettlementAccountDto> withdrawn = restTemplate.exchange(
                "/api/portal/settlement/account/" + first.id() + "/withdraw", HttpMethod.POST,
                entity(company.director(), null), SettlementAccountDto.class);
        assertThat(withdrawn.getBody().status()).isEqualTo("withdrawn");

        assertThat(submit(company.director(), json(ACCESS, "9876543210")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void onlyTheExecutiveDirectorMaySetWhereTheMoneyGoes() {
        String finance = staff(company.id(), "finance_officer");

        ResponseEntity<String> response = submitRaw(finance, json(GTBANK, "0123456789"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // Approving
    // ------------------------------------------------------------------

    @Test
    void approvalRegistersTheRecipientAndTheOldAccountIsKeptAsHistory() {
        bankKnows("0123456789", GTBANK, "SETTLE CO LIMITED");
        bankKnows("9876543210", ACCESS, "SETTLE CO LIMITED");
        when(paystack.createRecipient(eq("SETTLE CO LIMITED"), eq("0123456789"), eq(GTBANK), any()))
                .thenReturn("RCP_first");
        when(paystack.createRecipient(eq("SETTLE CO LIMITED"), eq("9876543210"), eq(ACCESS), any()))
                .thenReturn("RCP_second");

        SettlementAccountDto first = submit(company.director(), json(GTBANK, "0123456789")).getBody();
        assertThat(adminAction(first.id(), "approve", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        SettlementAccountDto second = submit(company.director(), json(ACCESS, "9876543210")).getBody();

        CompanySettlementDto meanwhile = companyView(company.director());
        assertThat(meanwhile.approved().id()).as("the approved account keeps receiving payouts meanwhile")
                .isEqualTo(first.id());
        assertThat(meanwhile.pending().id()).isEqualTo(second.id());

        adminAction(second.id(), "approve", null);

        assertThat(query("SELECT status || ':' || recipient_code FROM settlement_accounts WHERE id = '" + first.id() + "'"))
                .isEqualTo("SUPERSEDED:RCP_first");
        assertThat(query("SELECT status || ':' || recipient_code FROM settlement_accounts WHERE id = '" + second.id() + "'"))
                .isEqualTo("APPROVED:RCP_second");
        CompanySettlementDto after = companyView(company.director());
        assertThat(after.approved().id()).isEqualTo(second.id());
        assertThat(after.pending()).isNull();
        assertThat(after.history()).extracting(SettlementAccountDto::status).containsExactly("approved", "superseded");
    }

    @Test
    void ifPaystackIsDownApprovalChangesNothing() {
        bankKnows("0123456789", GTBANK, "SETTLE CO LIMITED");
        when(paystack.createRecipient(anyString(), anyString(), anyString(), any()))
                .thenThrow(new PaymentException.GatewayUnavailable());
        SettlementAccountDto account = submit(company.director(), json(GTBANK, "0123456789")).getBody();

        ResponseEntity<String> response = adminAction(account.id(), "approve", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(query("SELECT status || ':' || coalesce(recipient_code, '-') FROM settlement_accounts WHERE id = '"
                + account.id() + "'")).isEqualTo("PENDING:-");
    }

    @Test
    void aNameThatDoesntMatchIsAWarningTheReviewerSeesNotABlock() {
        bankKnows("0123456789", GTBANK, "JOHN ADEBAYO");
        when(paystack.createRecipient(anyString(), anyString(), anyString(), any())).thenReturn("RCP_x");
        SettlementAccountDto account = submit(company.director(), json(GTBANK, "0123456789")).getBody();

        SettlementAccountReviewDto review = restTemplate.exchange("/api/admin/settlement-accounts/" + account.id(),
                HttpMethod.GET, entity(adminToken(), null), SettlementAccountReviewDto.class).getBody();
        assertThat(review.nameMatchesCompany()).isFalse();
        assertThat(review.matchesOnboardingAccount()).as("nothing was declared at onboarding").isNull();

        assertThat(adminAction(account.id(), "approve", null).getStatusCode())
                .as("a person may still approve it — trading names differ legitimately").isEqualTo(HttpStatus.OK);
    }

    @Test
    void aMatchingNameIsNotFlagged() {
        bankKnows("0123456789", GTBANK, company.registeredName().toUpperCase() + " LIMITED");
        SettlementAccountDto account = submit(company.director(), json(GTBANK, "0123456789")).getBody();

        SettlementAccountReviewDto review = restTemplate.exchange("/api/admin/settlement-accounts/" + account.id(),
                HttpMethod.GET, entity(adminToken(), null), SettlementAccountReviewDto.class).getBody();

        assertThat(review.nameMatchesCompany()).isTrue();
    }

    @Test
    void aRejectionCarriesItsReasonToTheCompanyAndIsFinal() {
        bankKnows("0123456789", GTBANK, "JOHN ADEBAYO");
        SettlementAccountDto account = submit(company.director(), json(GTBANK, "0123456789")).getBody();

        adminAction(account.id(), "reject", "{\"reason\":\"This is a personal account\"}");

        SettlementAccountDto seen = companyView(company.director()).history().getFirst();
        assertThat(seen.status()).isEqualTo("rejected");
        assertThat(seen.decisionNote()).isEqualTo("This is a personal account");
        ResponseEntity<String> again = adminAction(account.id(), "approve", null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("SETTLEMENT_ACCOUNT_NOT_PENDING");
    }

    @Test
    void aCompanyDirectorCannotApproveTheirOwnAccount() {
        bankKnows("0123456789", GTBANK, "SETTLE CO LIMITED");
        SettlementAccountDto account = submit(company.director(), json(GTBANK, "0123456789")).getBody();

        ResponseEntity<String> response = restTemplate.exchange("/api/admin/settlement-accounts/" + account.id()
                + "/approve", HttpMethod.POST, entity(company.director(), null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // Isolation — the database, not the code
    // ------------------------------------------------------------------

    @Test
    void anotherCompanyCanNeitherSeeNorWithdrawYourAccount() {
        bankKnows("0123456789", GTBANK, "SETTLE CO LIMITED");
        SettlementAccountDto ours = submit(company.director(), json(GTBANK, "0123456789")).getBody();
        Company other = company();

        CompanySettlementDto theirView = companyView(other.director());
        assertThat(theirView.history()).isEmpty();
        ResponseEntity<String> withdraw = restTemplate.exchange("/api/portal/settlement/account/" + ours.id()
                + "/withdraw", HttpMethod.POST, entity(other.director(), null), String.class);
        assertThat(withdraw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(query("SELECT status FROM settlement_accounts WHERE id = '" + ours.id() + "'")).isEqualTo("PENDING");
    }

    @Test
    void historyCanNeverBeDeletedByTheApp() {
        assertThat(query("SELECT has_table_privilege('" + APP_ROLE + "', 'settlement_accounts', 'DELETE')"))
                .isEqualTo("f");
        assertThat(query("SELECT relrowsecurity::text || relforcerowsecurity::text FROM pg_class "
                + "WHERE relname = 'settlement_accounts'")).isEqualTo("truetrue");
    }

    // ------------------------------------------------------------------

    private void bankKnows(String accountNumber, String bankCode, String name) {
        when(paystack.resolveAccountName(accountNumber, bankCode)).thenReturn(Optional.of(name));
    }

    private static String json(String bankCode, String accountNumber) {
        return "{\"bankCode\":\"" + bankCode + "\",\"accountNumber\":\"" + accountNumber + "\"}";
    }

    private ResponseEntity<SettlementAccountDto> submit(String token, String json) {
        ResponseEntity<SettlementAccountDto> response = restTemplate.exchange("/api/portal/settlement/account",
                HttpMethod.POST, entity(token, json), SettlementAccountDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private ResponseEntity<String> submitRaw(String token, String json) {
        return restTemplate.exchange("/api/portal/settlement/account", HttpMethod.POST, entity(token, json), String.class);
    }

    private CompanySettlementDto companyView(String token) {
        ResponseEntity<CompanySettlementDto> response = restTemplate.exchange("/api/portal/settlement/account",
                HttpMethod.GET, entity(token, null), CompanySettlementDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> adminAction(UUID accountId, String action, String json) {
        return restTemplate.exchange("/api/admin/settlement-accounts/" + accountId + "/" + action, HttpMethod.POST,
                entity(adminToken(), json), String.class);
    }

    private Company company() {
        String rc = "RC-" + UUID.randomUUID();
        String registeredName = "Settle Co " + rc;
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto(registeredName, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "invited+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken(), request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = response.getBody().id();
        execute("UPDATE organizations SET verification_state = 'VERIFIED', status = 'ACTIVE' WHERE id = '" + id + "'");
        String email = "ed+" + UUID.randomUUID() + "@example.com";
        return new Company(id, registeredName, email, staff(id, "executive_director", email));
    }

    private String staff(UUID tenantId, String roleCode) {
        return staff(tenantId, roleCode, "staff+" + UUID.randomUUID() + "@example.com");
    }

    private String staff(UUID tenantId, String roleCode, String email) {
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + tenantId + "', status = 'ACTIVE' WHERE lower(email) = lower('"
                + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, NULL FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "'");
        return login(email);
    }

    private String adminToken() {
        return login(ADMIN_EMAIL);
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private static HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static long count(String sql) {
        return Long.parseLong(query(sql));
    }

    private static String query(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Company(UUID id, String registeredName, String directorEmail, String director) {
    }
}
