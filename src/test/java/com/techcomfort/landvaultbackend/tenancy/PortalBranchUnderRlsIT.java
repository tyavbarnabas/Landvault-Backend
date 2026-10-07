package com.techcomfort.landvaultbackend.tenancy;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.PortalBranchDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-1..TB-3: a tenant manages its own branches. Wired to the restricted app
 * role, so changeset 021's branch policy is what's actually enforcing which
 * branches each caller sees. See AGENTS.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PortalBranchUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_branch_it";
    private static final String APP_ROLE_PASSWORD = "branch-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";

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

    private UUID tenantId;
    private String director;

    @BeforeEach
    void setUp() {
        tenantId = tenant();
        director = staffToken(tenantId, "executive_director", null);
    }

    /** TB-1: no support ticket — the Executive Director creates branches, and they are immediately usable. */
    @Test
    void anExecutiveDirectorCreatesBranchesThatAreImmediatelyUsable() {
        UUID doubleKing = createBranch(director, "Double King");
        createBranch(director, "Harmony");

        assertThat(listBranches(director)).extracting("name").containsExactly("Double King", "Harmony");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'tenant.branch_created' "
                + "AND target_id = '" + doubleKing + "'")).contains("Double King");

        // Usable straight away: an estate can be created in it.
        assertThat(restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(director, "{\"name\":\"DK Gardens\",\"state\":\"FCT\",\"branchId\":\"" + doubleKing + "\"}"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aCompanyWithNoBranchesHasNone() {
        assertThat(listBranches(director)).as("never an invented Head Office").isEmpty();
    }

    @Test
    void branchNamesAreUniqueWithinTheCompanyIgnoringCase() {
        createBranch(director, "Double King");

        ResponseEntity<String> clash = restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(director, "{\"name\":\"double king\"}"), String.class);

        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody()).contains("BRANCH_NAME_TAKEN");

        // Another company may use the same name.
        String rival = staffToken(tenant(), "executive_director", null);
        assertThat(restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(rival, "{\"name\":\"Double King\"}"), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** TB-2. */
    @Test
    void aBranchCanBeRenamedButNotOntoAnotherBranchsName() {
        UUID doubleKing = createBranch(director, "Double King");
        createBranch(director, "Harmony");

        assertThat(rename(director, doubleKing, "Double King Lekki").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rename(director, doubleKing, "Harmony").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rename(director, doubleKing, "Double King Lekki").getStatusCode())
                .as("unchanged is not an error").isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'tenant.branch_updated' "
                + "AND target_id = '" + doubleKing + "'")).as("one real rename, one recorded").isEqualTo("1");
    }

    /** The office: optional, standardised state, blank clears, bad values refused. */
    @Test
    void aBranchCarriesAnOptionalOfficeAddressAndContact() {
        ResponseEntity<PortalBranchDto> created = restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(director, """
                        {"name":"Double King","street":"12 Admiralty Way","city":"Lekki","state":"Lagos State",
                         "phone":"+234 801 234 5678","email":"lekki@doubleking.example"}
                        """), PortalBranchDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PortalBranchDto branch = created.getBody();
        assertThat(branch.state()).as("standardised, as for estates").isEqualTo("Lagos");
        assertThat(branch.phone()).isEqualTo("+234 801 234 5678");
        assertThat(queryString("SELECT state_code FROM branches WHERE id = '" + branch.id() + "'")).isEqualTo("NG-LA");

        PortalBranchDto cleared = restTemplate.exchange("/api/portal/branches/" + branch.id(), HttpMethod.PUT,
                entity(director, "{\"phone\":\"\",\"city\":\"Victoria Island\"}"), PortalBranchDto.class).getBody();
        assertThat(cleared.phone()).as("blank clears").isNull();
        assertThat(cleared.city()).isEqualTo("Victoria Island");
        assertThat(cleared.street()).as("left out, unchanged").isEqualTo("12 Admiralty Way");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'tenant.branch_updated' "
                + "AND target_id = '" + branch.id() + "'")).contains("city 'Lekki' -> 'Victoria Island'");

        UUID plain = createBranch(director, "Harmony");
        assertThat(listBranches(director)).filteredOn(b -> b.id().equals(plain)).singleElement()
                .satisfies(b -> assertThat(b.street()).as("never invented").isNull());

        assertThat(restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(director, "{\"name\":\"Bad State\",\"state\":\"Atlantis\"}"), String.class).getBody())
                .contains("UNKNOWN_STATE");
        assertThat(restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(director, "{\"name\":\"Bad Contact\",\"phone\":\"call me\",\"email\":\"nope\"}"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** TB-3: the control that matters — nobody branch-scoped can carve out a sibling branch. */
    @Test
    void branchScopedStaffCannotCreateOrRenameBranches() {
        UUID doubleKing = createBranch(director, "Double King");
        String manager = staffToken(tenantId, "branch_manager", doubleKing);

        assertThat(restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(manager, "{\"name\":\"My Own Branch\"}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rename(manager, doubleKing, "Renamed By Manager").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Even holding the permission, a branch-scoped caller is refused by the service itself.
        String scopedDirector = staffToken(tenantId, "executive_director", doubleKing);
        ResponseEntity<String> refused = restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(scopedDirector, "{\"name\":\"Sneaky\"}"), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("BRANCHES_REQUIRE_COMPANY_WIDE_SCOPE");
    }

    /** RLS (changeset 021): a branch manager sees their own branch only. */
    @Test
    void aBranchManagerSeesOnlyTheirOwnBranch() {
        UUID doubleKing = createBranch(director, "Double King");
        createBranch(director, "Harmony");
        String manager = staffToken(tenantId, "branch_manager", doubleKing);

        assertThat(listBranches(manager)).extracting("name").containsExactly("Double King");
    }

    @Test
    void anotherCompanysBranchCannotBeRenamed() {
        UUID theirs = createBranch(staffToken(tenant(), "executive_director", null), "Theirs");

        assertThat(rename(director, theirs, "Mine Now").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aBlankNameIsRefused() {
        assertThat(restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(director, "{\"name\":\"  \"}"), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- fixtures ---

    private UUID createBranch(String token, String name) {
        ResponseEntity<PortalBranchDto> created = restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(token, "{\"name\":\"" + name + "\"}"), PortalBranchDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().id();
    }

    private List<PortalBranchDto> listBranches(String token) {
        return restTemplate.exchange("/api/portal/branches", HttpMethod.GET, entity(token, null),
                new ParameterizedTypeReference<List<PortalBranchDto>>() {
                }).getBody();
    }

    private ResponseEntity<String> rename(String token, UUID branch, String name) {
        return restTemplate.exchange("/api/portal/branches/" + branch, HttpMethod.PUT,
                entity(token, "{\"name\":\"" + name + "\"}"), String.class);
    }

    private UUID tenant() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("State Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken(), request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String staffToken(UUID owningTenantId, String roleCode, UUID scopedBranchId) {
        String email = "staff+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, "
                + (scopedBranchId == null ? "NULL" : "'" + scopedBranchId + "'") + " FROM users u, roles r "
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

    private static String queryString(String sql) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String sql) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
