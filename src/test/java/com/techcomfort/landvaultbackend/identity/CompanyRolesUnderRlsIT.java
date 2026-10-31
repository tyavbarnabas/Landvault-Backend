package com.techcomfort.landvaultbackend.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AssignableRoleDto;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.CreateRoleRequest;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.PortalPermissionDto;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Company-made roles. The wall that matters: {@code roles} has no RLS, so the
 * service's tenant condition is the only thing keeping one company's roles from
 * another — every test here that names a second company is about that. Wired to
 * the restricted app role like the other staff ITs. See AGENTS.md, "Company-made roles".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class CompanyRolesUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_roles_it";
    private static final String APP_ROLE_PASSWORD = "roles-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";
    private static final String DELIVERY_LOGGER =
            "com.techcomfort.landvaultbackend.identity.internal.service.LoggingInvitationDeliveryService";

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

    private ListAppender<ILoggingEvent> deliveries;
    private UUID tenantId;
    private String director;
    private UUID branch;

    @BeforeEach
    void setUp() {
        deliveries = new ListAppender<>();
        deliveries.start();
        ((Logger) LoggerFactory.getLogger(DELIVERY_LOGGER)).addAppender(deliveries);
        tenantId = tenant();
        director = staffToken(tenantId, "executive_director", null);
        branch = createBranch(director, "Double King");
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DELIVERY_LOGGER)).detachAppender(deliveries);
    }

    @Test
    void anExecutiveDirectorCreatesARoleAndCanInviteSomeoneIntoIt() {
        ResponseEntity<AssignableRoleDto> created = create(director, "Accounts Clerk", "either",
                "portal.estates.view", "portal.payments.verify");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AssignableRoleDto role = created.getBody();
        assertThat(role.custom()).isTrue();
        assertThat(role.code()).startsWith("custom_");
        assertThat(role.scope()).isEqualTo("either");
        assertThat(role.permissions()).containsExactly("portal.estates.view", "portal.payments.verify");
        assertThat(role.canGrant()).isTrue();
        assertThat(roles(director)).extracting(AssignableRoleDto::code).contains(role.code(), "executive_director");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'role.created'"))
                .contains("Accounts Clerk").contains("portal.payments.verify");

        // Used exactly like a standard role: invited into a branch, accepted, and the person gets exactly its permissions.
        String email = "clerk+" + UUID.randomUUID() + "@example.com";
        assertThat(invite(director, email, role.code(), branch).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<AuthResponse> accepted = restTemplate.postForEntity("/api/auth/invitations/accept",
                json(null, "{\"token\":\"" + lastToken() + "\",\"password\":\"" + PASSWORD + "\"}"), AuthResponse.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().user().permissions())
                .containsExactlyInAnyOrder("portal.estates.view", "portal.payments.verify");
        assertThat(accepted.getBody().user().branchId()).as("walled to the branch").isEqualTo(branch);
    }

    /** The wall: another company never sees, uses, edits or deletes your role. */
    @Test
    void anotherCompanyCanNeitherSeeNorUseNorTouchTheRole() {
        String code = create(director, "Site Supervisor", "either", "portal.estates.view").getBody().code();
        String rival = staffToken(tenant(), "executive_director", null);

        assertThat(roles(rival)).extracting(AssignableRoleDto::code).doesNotContain(code)
                .contains("executive_director");
        ResponseEntity<String> invited = invite(rival, "x+" + UUID.randomUUID() + "@example.com", code, null);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invited.getBody()).contains("ROLE_NOT_INVITABLE");
        assertThat(update(rival, code, "{\"name\":\"Hijacked\"}").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(rival, code).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(rival, "Site Supervisor", "either", "portal.estates.view").getStatusCode())
                .as("names are per company").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void onlyCompanyPermissionsTheCallerHoldsCanGoIntoARole() {
        assertThat(createRaw(director, "Sneaky", "company", "admin.tenants.manage").getBody())
                .contains("UNKNOWN_PERMISSION");
        assertThat(createRaw(director, "Sneaky", "company", "client.checkout.reserve").getBody())
                .contains("UNKNOWN_PERMISSION");
        assertThat(createRaw(director, "Sneaky", "company", "portal.made.up").getBody())
                .contains("UNKNOWN_PERMISSION");
        assertThat(restTemplate.exchange("/api/portal/roles", HttpMethod.POST, json(director,
                        "{\"name\":\"Empty\",\"scope\":\"company\",\"permissions\":[]}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // SI-4: someone allowed to manage roles, but holding less than a director, can't bundle more than they hold.
        String deputy = create(director, "Deputy", "company", "portal.roles.manage", "portal.estates.view")
                .getBody().code();
        String deputyToken = staffToken(tenantId, deputy, null);
        ResponseEntity<String> beyond = createRaw(deputyToken, "Payments", "either", "portal.payments.verify");
        assertThat(beyond.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(beyond.getBody()).contains("CANNOT_GRANT_PERMISSION");
        assertThat(createRaw(deputyToken, "Viewer", "either", "portal.estates.view").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // ...nor weaken a role carrying more than they hold.
        String strong = create(director, "Finance Lead", "either", "portal.payments.verify").getBody().code();
        ResponseEntity<String> weakened = update(deputyToken, strong, "{\"permissions\":[\"portal.estates.view\"]}");
        assertThat(weakened.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(weakened.getBody()).contains("CANNOT_MANAGE_ROLE");
    }

    /** Decided with the user: a role that could reach branch staff can't carry permissions that only work company-wide. */
    @Test
    void companyWideOnlyPermissionsNeedACompanyRole() {
        for (String scope : List.of("branch", "either")) {
            ResponseEntity<String> refused = createRaw(director, "HR " + scope, scope, "portal.staff.invite");
            assertThat(refused.getStatusCode()).as(scope).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody()).contains("PERMISSION_REQUIRES_COMPANY_SCOPE").contains("portal.staff.invite");
        }
        assertThat(createRaw(director, "HR", "company", "portal.staff.invite").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        List<PortalPermissionDto> permissions = restTemplate.exchange("/api/portal/permissions", HttpMethod.GET,
                json(director, null), new ParameterizedTypeReference<List<PortalPermissionDto>>() {
                }).getBody();
        assertThat(permissions).extracting(PortalPermissionDto::code).allMatch(c -> c.startsWith("portal."))
                .contains("portal.roles.manage");
        assertThat(permissions).filteredOn(PortalPermissionDto::requiresCompanyWide).extracting(PortalPermissionDto::code)
                .containsExactlyInAnyOrder("portal.branches.manage", "portal.staff.invite",
                        "portal.settlement.manage", "portal.plans.revoke", "portal.roles.manage",
                        "portal.documents.manage", "portal.resale.consent");
        assertThat(permissions).allMatch(p -> p.label() != null && p.held());
    }

    /** The frontend's role editor groups checkboxes; the grouping and both orders are HomeTrue's, from changeset 085. */
    @Test
    void permissionsComeGroupedInHomeTruesOrder() {
        List<PortalPermissionDto> permissions = restTemplate.exchange("/api/portal/permissions", HttpMethod.GET,
                json(director, null), new ParameterizedTypeReference<List<PortalPermissionDto>>() {
                }).getBody();
        assertThat(permissions).allMatch(p -> p.group() != null && p.groupLabel() != null);
        assertThat(permissions).extracting(PortalPermissionDto::code).containsExactly(
                "portal.estates.view", "portal.estates.manage", "portal.resale.consent",
                "portal.payments.verify", "portal.settlement.manage", "portal.plans.revoke",
                "portal.branches.manage",
                "portal.staff.invite", "portal.staff.request", "portal.roles.manage",
                "portal.documents.view", "portal.documents.manage");
        assertThat(permissions).extracting(PortalPermissionDto::group).containsExactly(
                "estates", "estates", "estates", "finance", "finance", "finance", "branches", "staff", "staff", "staff",
                "documents", "documents");
        assertThat(permissions).extracting(PortalPermissionDto::groupLabel).containsSubsequence(
                "Estates", "Money", "Branches", "Staff and roles", "Documents");
        // A company must always be able to make a role carrying every portal permission.
        assertThat(permissions).hasSizeLessThanOrEqualTo(CreateRoleRequest.MAX_PERMISSIONS);
    }

    /** A new portal.* permission seeded without a group is refused by the database, not shown ungrouped. */
    @Test
    void anUngroupedPortalPermissionCannotBeSeeded() throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("INSERT INTO permissions (id, created_at, created_by, deleted, code, "
                    + "description, label) VALUES (gen_random_uuid(), now(), 'test', false, 'portal.ungrouped.x', "
                    + "'x', 'X')"))
                    .hasMessageContaining("chk_permissions_portal_grouped");
        }
    }

    @Test
    void onlyCompanyWideRoleManagersMayManageRoles() {
        String manager = staffToken(tenantId, "branch_manager", branch);
        assertThat(createRaw(manager, "Mine", "branch", "portal.estates.view").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders narrowed = new HttpHeaders();
        narrowed.setBearerAuth(director);
        narrowed.setContentType(MediaType.APPLICATION_JSON);
        narrowed.set("X-Branch-Id", branch.toString());
        ResponseEntity<String> fromBranchView = restTemplate.exchange("/api/portal/roles", HttpMethod.POST,
                new HttpEntity<>(body("Narrow", "branch", "portal.estates.view"), narrowed), String.class);
        assertThat(fromBranchView.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fromBranchView.getBody()).contains("ROLES_REQUIRE_COMPANY_WIDE_SCOPE");
    }

    @Test
    void namesAreUniqueInTheCompanyAndNeverAStandardRolesName() {
        create(director, "Accounts Clerk", "either", "portal.estates.view");
        assertThat(createRaw(director, "accounts clerk", "either", "portal.estates.view").getBody())
                .contains("ROLE_NAME_TAKEN");
        assertThat(createRaw(director, "Branch Manager", "either", "portal.estates.view").getBody())
                .as("would read as HomeTrue's").contains("ROLE_NAME_TAKEN");
    }

    @Test
    void standardRolesAreLocked() {
        ResponseEntity<String> edited = update(director, "finance_officer", "{\"name\":\"Money\"}");
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(edited.getBody()).contains("STANDARD_ROLE_LOCKED");
        assertThat(delete(director, "finance_officer").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** An edit reaches the holder at their next sign-in or refresh — never claimed to be instant. */
    @Test
    void editingARoleChangesWhatItsHoldersGetAtTheirNextSignIn() {
        String code = create(director, "Clerk", "either", "portal.estates.view").getBody().code();
        String email = "holder+" + UUID.randomUUID() + "@example.com";
        staffToken(tenantId, code, null, email);

        ResponseEntity<AssignableRoleDto> edited = restTemplate.exchange("/api/portal/roles/" + code, HttpMethod.PUT,
                json(director, "{\"name\":\"Senior Clerk\",\"permissions\":[\"portal.estates.view\",\"portal.payments.verify\"]}"),
                AssignableRoleDto.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().name()).isEqualTo("Senior Clerk");
        assertThat(loginResponse(email).user().permissions())
                .containsExactlyInAnyOrder("portal.estates.view", "portal.payments.verify");

        assertThat(update(director, code, "{\"scope\":\"company\"}").getBody()).contains("ROLE_SCOPE_IMMUTABLE");
        assertThat(update(director, code, "{\"scope\":\"either\",\"name\":\"Senior Clerk\"}").getStatusCode())
                .as("echoing the current scope back is fine").isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'role.updated'"))
                .as("the no-op wrote nothing").isEqualTo("1");
    }

    @Test
    void aRoleCanBeDeletedOnlyWhenNobodyHoldsItOrIsOfferedIt() {
        String code = create(director, "Temp", "either", "portal.estates.view").getBody().code();
        String email = "temp+" + UUID.randomUUID() + "@example.com";
        assertThat(invite(director, email, code, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> offered = delete(director, code);
        assertThat(offered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(offered.getBody()).contains("ROLE_IN_USE").contains("invitation");

        execute("UPDATE staff_invitations SET revoked_at = now() WHERE lower(email) = lower('" + email + "')");
        String holderEmail = "held+" + UUID.randomUUID() + "@example.com";
        staffToken(tenantId, code, null, holderEmail);
        assertThat(delete(director, code).getBody()).contains("ROLE_IN_USE").contains("holds it");

        execute("DELETE FROM user_roles WHERE role_id = (SELECT id FROM roles WHERE code = '" + code + "')");
        assertThat(delete(director, code).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(roles(director)).extracting(AssignableRoleDto::code).doesNotContain(code);
        assertThat(createRaw(director, "Temp", "either", "portal.estates.view").getStatusCode())
                .as("the name is free again").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aCompanyHasAtMostThirtyRolesOfItsOwn() {
        for (int i = 1; i <= 30; i++) {
            assertThat(createRaw(director, "Role " + i, "either", "portal.estates.view").getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<String> thirtyFirst = createRaw(director, "Role 31", "either", "portal.estates.view");
        assertThat(thirtyFirst.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(thirtyFirst.getBody()).contains("ROLE_LIMIT_REACHED");
    }

    // --- fixtures ---

    private ResponseEntity<AssignableRoleDto> create(String token, String name, String scope, String... permissions) {
        ResponseEntity<AssignableRoleDto> response = restTemplate.exchange("/api/portal/roles", HttpMethod.POST,
                json(token, body(name, scope, permissions)), AssignableRoleDto.class);
        assertThat(response.getStatusCode()).as("creating " + name).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private ResponseEntity<String> createRaw(String token, String name, String scope, String... permissions) {
        return restTemplate.exchange("/api/portal/roles", HttpMethod.POST, json(token, body(name, scope, permissions)),
                String.class);
    }

    private static String body(String name, String scope, String... permissions) {
        return "{\"name\":\"" + name + "\",\"scope\":\"" + scope + "\",\"permissions\":[\""
                + String.join("\",\"", permissions) + "\"]}";
    }

    private ResponseEntity<String> update(String token, String code, String json) {
        return restTemplate.exchange("/api/portal/roles/" + code, HttpMethod.PUT, json(token, json), String.class);
    }

    private ResponseEntity<String> delete(String token, String code) {
        return restTemplate.exchange("/api/portal/roles/" + code, HttpMethod.DELETE, json(token, null), String.class);
    }

    private List<AssignableRoleDto> roles(String token) {
        return restTemplate.exchange("/api/portal/roles", HttpMethod.GET, json(token, null),
                new ParameterizedTypeReference<List<AssignableRoleDto>>() {
                }).getBody();
    }

    private ResponseEntity<String> invite(String token, String email, String role, UUID branchId) {
        String body = "{\"email\":\"" + email + "\",\"firstName\":\"Test\",\"lastName\":\"Staff\",\"roleCode\":\""
                + role + "\"" + (branchId == null ? "" : ",\"branchId\":\"" + branchId + "\"") + "}";
        return restTemplate.exchange("/api/portal/staff/invitations", HttpMethod.POST, json(token, body), String.class);
    }

    private String lastToken() {
        List<String> links = deliveries.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(" link=")).toList();
        assertThat(links).as("an invitation was delivered").isNotEmpty();
        String link = links.getLast();
        return link.substring(link.indexOf("#token=") + 7).trim();
    }

    private UUID createBranch(String token, String name) {
        ResponseEntity<String> created = restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                json(token, "{\"name\":\"" + name + "\"}"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = created.getBody();
        int at = body.indexOf("\"id\":\"") + 6;
        return UUID.fromString(body.substring(at, at + 36));
    }

    private UUID tenant() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Roles Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, json(login(ADMIN_EMAIL), request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String staffToken(UUID owningTenantId, String roleCode, UUID scopedBranchId) {
        return staffToken(owningTenantId, roleCode, scopedBranchId, "staff+" + UUID.randomUUID() + "@example.com");
    }

    private String staffToken(UUID owningTenantId, String roleCode, UUID scopedBranchId, String email) {
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + owningTenantId + "', status = 'ACTIVE' WHERE lower(email) = lower('"
                + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, "
                + (scopedBranchId == null ? "NULL" : "'" + scopedBranchId + "'") + " FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "' AND r.deleted = false");
        return login(email);
    }

    private String login(String email) {
        return loginResponse(email).token();
    }

    private AuthResponse loginResponse(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpEntity<Object> json(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
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
