package com.techcomfort.landvaultbackend.identity;

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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.techcomfort.landvaultbackend.identity.dto.StaffInvitationDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SI-1..SI-7: staff invitations, end to end over HTTP under the restricted
 * app role. The link is read from the test-only logging delivery, exactly as
 * PasswordResetIT reads codes. See AGENTS.md, "Tenant self-service".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class StaffInvitationUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_invitation_it";
    private static final String APP_ROLE_PASSWORD = "invitation-it-password";
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
    private UUID doubleKing;
    private UUID harmony;
    private String directorEmail;
    private String lastStaffEmail;

    @BeforeEach
    void setUp() {
        deliveries = new ListAppender<>();
        deliveries.start();
        ((Logger) LoggerFactory.getLogger(DELIVERY_LOGGER)).addAppender(deliveries);
        tenantId = tenant();
        director = staffToken(tenantId, "executive_director", null);
        directorEmail = lastStaffEmail;
        // A real Executive Director joined through an invitation, so is ACTIVE.
        execute("UPDATE users SET status = 'ACTIVE' WHERE lower(email) = lower('" + directorEmail + "')");
        doubleKing = createBranch(director, "Double King");
        harmony = createBranch(director, "Harmony");
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DELIVERY_LOGGER)).detachAppender(deliveries);
    }

    // --- SI-1, SI-3: the whole journey ---

    /**
     * The branch wall finally has someone standing on it: invited, accepted,
     * signed in, and walled to Double King — with no buyer role to dissolve it.
     */
    @Test
    void aBranchManagerIsInvitedAcceptsAndIsWalledToTheirBranch() {
        String email = "bola+" + UUID.randomUUID() + "@doubleking.example";
        ResponseEntity<String> invited = invite(director, email, "branch_manager", doubleKing);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = lastToken();
        assertThat(invited.getBody()).as("the token travels by email only").doesNotContain(token);
        assertThat(lastLink()).as("after #, so it never reaches a server log").contains("/accept-invitation#token=");

        assertThat(post("/api/auth/invitations/preview", null, "{\"token\":\"" + token + "\"}").getBody())
                .contains("\"roleName\"").contains("\"branchName\":\"Double King\"").contains(email);

        ResponseEntity<String> accepted = post("/api/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).as("signed in, like login").contains("lv_refresh=");
        assertThat(accepted.getBody()).contains("\"branchId\":\"" + doubleKing + "\"").contains("portal.estates.view");

        UUID userId = UUID.fromString(queryString("SELECT id FROM users WHERE email = '" + email + "'"));
        assertThat(queryString("SELECT tenant_id FROM users WHERE id = '" + userId + "'")).isEqualTo(tenantId.toString());
        assertThat(queryString("SELECT status FROM users WHERE id = '" + userId + "'")).isEqualTo("ACTIVE");
        assertThat(queryString("SELECT string_agg(r.code || ':' || coalesce(ur.scoped_branch_id::text, 'company'), ',') "
                + "FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = '" + userId + "'"))
                .as("only the invited role — never buyer").isEqualTo("branch_manager:" + doubleKing);

        String manager = login(email);
        assertThat(restTemplate.exchange("/api/portal/branches", HttpMethod.GET, entity(manager, null), String.class).getBody())
                .contains("Double King").doesNotContain("Harmony");

        assertThat(post("/api/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"another one\"}").getBody())
                .as("single-use").contains("INVITATION_INVALID");
        // Two invitations: the fixture's own first Executive Director, and this one.
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'staff.invited' "
                + "AND tenant_id = '" + tenantId + "'")).isEqualTo("2");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'staff.invitation_accepted' "
                + "AND tenant_id = '" + tenantId + "'")).isEqualTo("1");
    }

    // --- the role's scope ---

    /** The invited person's own name counts as personal; a refusal must not burn the link. */
    @Test
    void aWeakPasswordOnAcceptLeavesTheLinkUsable() {
        String email = "kemi.obi+" + UUID.randomUUID() + "@example.com";
        invite(director, email, "sales_manager", null);
        String token = lastToken();

        ResponseEntity<String> weak = post("/api/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"staffTest2026\"}");
        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(weak.getBody()).contains("WEAK_PASSWORD").contains("\"fieldErrors\":{\"password\":")
                .as("the invitation's first name is Test").contains("Don't use your name or email");
        assertThat(post("/api/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"harmattan7\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theRolesScopeDecidesWhetherABranchIsRequiredForbiddenOrOptional() {
        assertThat(invite(director, fresh(), "branch_manager", null).getBody()).contains("ROLE_SCOPE_MISMATCH");
        assertThat(invite(director, fresh(), "executive_director", doubleKing).getBody()).contains("ROLE_SCOPE_MISMATCH");
        assertThat(invite(director, fresh(), "sales_manager", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invite(director, fresh(), "sales_manager", doubleKing).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(queryString("SELECT scope FROM roles WHERE code = 'executive_director'")).as("data, not code").isEqualTo("COMPANY");
    }

    @Test
    void onlyTenantRolesAndTheCompanysOwnBranchesCanBeInvitedInto() {
        assertThat(invite(director, fresh(), "buyer", null).getBody()).contains("ROLE_NOT_INVITABLE");
        assertThat(invite(director, fresh(), "super_admin", null).getBody()).contains("ROLE_NOT_INVITABLE");
        UUID theirs = createBranch(staffToken(tenant(), "executive_director", null), "Theirs");
        assertThat(invite(director, fresh(), "sales_manager", theirs).getBody()).contains("BRANCH_NOT_FOUND");
    }

    // --- SI-2 and the buyer collision ---

    @Test
    void anEmailThatAlreadyHasAnAccountIsRefused() {
        String buyer = "buyer+" + UUID.randomUUID() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Ada", "Buyer", buyer, "+2348000000000", PASSWORD, "NG", Currency.NGN), String.class);

        ResponseEntity<String> refused = invite(director, buyer, "sales_manager", null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("EMAIL_HAS_ACCOUNT");
    }

    @Test
    void aSecondOpenInvitationForTheSamePersonIsRefused() {
        String email = fresh();
        invite(director, email, "sales_manager", null);

        assertThat(invite(director, email.toUpperCase(), "sales_manager", null).getBody()).contains("INVITATION_ALREADY_PENDING");
    }

    // --- SI-4: who may invite, and what ---

    @Test
    void branchScopedStaffCannotInvite() {
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        assertThat(invite(manager, fresh(), "sales_manager", doubleKing).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Even holding the permission, a branch-scoped caller is refused by the service.
        String scopedDirector = staffToken(tenantId, "executive_director", doubleKing);
        assertThat(invite(scopedDirector, fresh(), "sales_manager", doubleKing).getBody())
                .contains("INVITATIONS_REQUIRE_COMPANY_WIDE_SCOPE");
    }

    /** Never grant what you don't hold: give a role a permission the Executive Director lacks. */
    @Test
    void aRoleCarryingAPermissionTheInviterLacksCannotBeGranted() {
        execute("INSERT INTO role_permissions (id, created_at, created_by, deleted, role_id, permission_id) "
                + "SELECT gen_random_uuid(), now(), 'test', false, r.id, p.id FROM roles r, permissions p "
                + "WHERE r.code = 'legal_officer' AND p.code = 'admin.audit.view'");
        try {
            ResponseEntity<String> refused = invite(director, fresh(), "legal_officer", null);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody()).contains("CANNOT_GRANT_ROLE");
        } finally {
            execute("DELETE FROM role_permissions WHERE created_by = 'test'");
        }
    }

    // --- SI-5 revoke, SI-6 resend, expiry ---

    @Test
    void aRevokedInvitationStopsWorkingAtOnce() {
        invite(director, fresh(), "sales_manager", null);
        String token = lastToken();
        UUID id = invitationIds().getFirst();

        assertThat(revoke(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview(token).getBody()).contains("INVITATION_INVALID");
        assertThat(revoke(id).getBody()).contains("INVITATION_NOT_OPEN");
    }

    /** A new link replaces the old one — never two live links — and resending is rate-limited. */
    @Test
    void resendingReplacesTheLinkAndIsRateLimited() {
        invite(director, fresh(), "sales_manager", null);
        String first = lastToken();
        UUID id = invitationIds().getFirst();

        assertThat(resend(id).getBody()).as("moments after sending").contains("INVITATION_RESEND_TOO_SOON");

        execute("UPDATE staff_invitations SET last_sent_at = now() - interval '1 hour' WHERE id = '" + id + "'");
        assertThat(resend(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = lastToken();
        assertThat(second).isNotEqualTo(first);
        assertThat(preview(first).getBody()).as("the old link is dead").contains("INVITATION_INVALID");
        assertThat(preview(second).getStatusCode()).isEqualTo(HttpStatus.OK);

        execute("UPDATE staff_invitations SET send_count = 5, last_sent_at = now() - interval '1 hour' WHERE id = '" + id + "'");
        assertThat(resend(id).getBody()).contains("INVITATION_RESEND_LIMIT_REACHED");
    }

    /** Unknown, expired, revoked and used all get the same answer. */
    @Test
    void everyBadLinkGetsTheSameAnswer() {
        invite(director, fresh(), "sales_manager", null);
        String expired = lastToken();
        execute("UPDATE staff_invitations SET expires_at = now() - interval '1 minute' WHERE token_hash IS NOT NULL "
                + "AND tenant_id = '" + tenantId + "'");

        String unknownBody = preview("never-issued").getBody();
        assertThat(preview(expired).getBody()).isEqualTo(unknownBody);
        assertThat(restTemplate.exchange("/api/portal/staff/invitations", HttpMethod.GET, entity(director, null), String.class)
                .getBody()).contains("\"status\":\"expired\"");
    }

    @Test
    void aSuspendedCompanyCanNeitherInviteNorBeJoined() {
        invite(director, fresh(), "sales_manager", null);
        String token = lastToken();
        execute("UPDATE organizations SET status = 'SUSPENDED' WHERE id = '" + tenantId + "'");

        assertThat(post("/api/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}").getBody()).contains("TENANT_NOT_ACTIVE");
        assertThat(invite(director, fresh(), "sales_manager", null).getBody()).contains("TENANT_NOT_ACTIVE");
    }

    // --- the first Executive Director ---

    /** Closes the old TODO: no account with a password nobody received — an invitation instead. */
    @Test
    void aNewTenantsFirstExecutiveDirectorIsInvitedNotGivenAnUnusablePassword() {
        UUID newTenant = tenant();
        String email = queryString("SELECT email FROM staff_invitations WHERE tenant_id = '" + newTenant + "'");
        String token = lastToken();

        assertThat(queryString("SELECT count(*) FROM users WHERE lower(email) = lower('" + email + "')"))
                .as("no account until the invitation is accepted").isEqualTo("0");
        assertThat(queryString("SELECT r.code FROM staff_invitations i JOIN roles r ON r.id = i.role_id "
                + "WHERE i.tenant_id = '" + newTenant + "'")).isEqualTo("executive_director");

        assertThat(post("/api/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}").getBody())
                .contains("portal.staff.invite").contains("portal.branches.manage");
    }

    // --- a branch asks, head office approves ---

    /** The whole journey: asked by Double King, approved by head office, joined into Double King. */
    @Test
    void aBranchRequestIsApprovedAndTheInviteeJoinsThatBranch() {
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        String email = "kemi+" + UUID.randomUUID() + "@doubleking.example";
        int linksBefore = links().size();

        ResponseEntity<String> requested = requestInvite(manager, email, "sales_manager", null);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(requested.getBody()).contains("\"status\":\"awaiting_approval\"")
                .as("the branch is always the requester's own").contains("\"branchId\":\"" + doubleKing + "\"");
        UUID id = idOf(requested);

        assertThat(links()).as("nothing reaches the invitee before approval").hasSize(linksBefore);
        assertThat(queryString("SELECT token_hash IS NULL FROM staff_invitations WHERE id = '" + id + "'"))
                .as("no link exists at all yet").isEqualTo("t");
        assertThatThrownBy(() -> execute("UPDATE staff_invitations SET token_hash = 'x' WHERE id = '" + id + "'"))
                .as("the database refuses a link before approval, whatever the code does")
                .hasMessageContaining("chk_staff_invitations_link_only_when_approved");
        assertThat(approvalNotices()).as("head office is told").anySatisfy(m ->
                assertThat(m).contains("to=" + directorEmail).contains("invitee=" + email));

        assertThat(post("/api/portal/staff/invitations/" + id + "/approve", manager, null).getStatusCode())
                .as("a branch manager can't approve their own request").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/portal/staff/invitations/" + id + "/resend", director, null).getBody())
                .contains("INVITATION_AWAITING_APPROVAL");

        ResponseEntity<String> approved = post("/api/portal/staff/invitations/" + id + "/approve", director, null);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).contains("\"status\":\"pending\"").contains("\"approvalRequired\":true");
        assertThat(links()).hasSize(linksBefore + 1);

        assertThat(post("/api/auth/invitations/accept", null,
                "{\"token\":\"" + lastToken() + "\",\"password\":\"" + PASSWORD + "\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT r.code || ':' || ur.scoped_branch_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "JOIN users u ON u.id = ur.user_id WHERE u.email = '" + email + "'")).isEqualTo("sales_manager:" + doubleKing);
        assertThat(queryString("SELECT string_agg(action, ',' ORDER BY created_at) FROM audit_log_entries "
                + "WHERE target_id = '" + id + "'"))
                .isEqualTo("staff.invitation_requested,staff.invitation_approved,staff.invitation_accepted");
    }

    @Test
    void aRejectedRequestNeverSendsAnythingAndTellsTheBranchWhy() {
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        String email = fresh();
        int linksBefore = links().size();
        UUID id = idOf(requestInvite(manager, email, "sales_manager", null));

        ResponseEntity<String> rejected = post("/api/portal/staff/invitations/" + id + "/reject", director,
                "{\"reason\":\"We are not hiring this quarter.\"}");
        assertThat(rejected.getBody()).contains("\"status\":\"rejected\"").contains("We are not hiring this quarter.");
        assertThat(restTemplate.exchange("/api/portal/staff/invitations", HttpMethod.GET, entity(manager, null), String.class)
                .getBody()).as("the branch sees the decision").contains("We are not hiring this quarter.");
        assertThat(post("/api/portal/staff/invitations/" + id + "/approve", director, null).getBody())
                .contains("INVITATION_NOT_AWAITING_APPROVAL");
        assertThat(post("/api/portal/staff/invitations/" + id + "/reject", director, "{\"reason\":\"\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(links()).hasSize(linksBefore);

        assertThat(requestInvite(manager, email, "sales_manager", null).getStatusCode())
                .as("a rejection is history; the branch can ask again").isEqualTo(HttpStatus.CREATED);
    }

    /** The requester's own branch, a branch-level role, and nothing they don't hold themselves. */
    @Test
    void aBranchCanOnlyAskForWhatItCouldHold() {
        String manager = staffToken(tenantId, "branch_manager", doubleKing);

        assertThat(requestInvite(manager, fresh(), "sales_manager", harmony).getBody())
                .as("never another branch").contains("ROLE_SCOPE_MISMATCH");
        assertThat(requestInvite(manager, fresh(), "executive_director", null).getBody()).contains("ROLE_SCOPE_MISMATCH");
        // Asking grants nothing, so a branch may ask for a surveyor; the approver is checked instead.
        UUID surveyor = idOf(requestInvite(manager, fresh(), "surveyor_project_manager", null));
        assertThat(post("/api/portal/staff/invitations/" + surveyor + "/approve", director, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(requestInvite(manager, fresh(), "buyer", null).getBody()).contains("ROLE_NOT_INVITABLE");
        assertThat(requestInvite(director, fresh(), "sales_manager", null).getBody())
                .as("head office invites directly").contains("INVITATION_REQUESTS_REQUIRE_BRANCH_SCOPE");
        String narrowedDirector = staffToken(tenantId, "executive_director", doubleKing);
        assertThat(requestInvite(narrowedDirector, fresh(), "sales_manager", null).getBody())
                .as("a director can't request and then approve it themselves")
                .contains("INVITATION_REQUESTS_REQUIRE_BRANCH_SCOPE");
    }

    @Test
    void onlyTheRequesterCanWithdrawARequest() {
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        String colleague = staffToken(tenantId, "branch_manager", doubleKing);
        UUID id = idOf(requestInvite(manager, fresh(), "sales_manager", null));

        assertThat(post("/api/portal/staff/invitations/" + id + "/cancel", colleague, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/portal/staff/invitations/" + id + "/cancel", manager, null).getBody())
                .contains("\"status\":\"revoked\"");
        assertThat(post("/api/portal/staff/invitations/" + id + "/approve", director, null).getBody())
                .contains("INVITATION_NOT_AWAITING_APPROVAL");
    }

    @Test
    void aLapsedRequestCannotBeApproved() {
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        UUID id = idOf(requestInvite(manager, fresh(), "sales_manager", null));
        assertThat(queryString("SELECT round(extract(epoch FROM expires_at - created_at) / 86400) FROM staff_invitations "
                + "WHERE id = '" + id + "'")).as("14 days to decide").isEqualTo("14");
        execute("UPDATE staff_invitations SET expires_at = now() - interval '1 minute' WHERE id = '" + id + "'");

        assertThat(post("/api/portal/staff/invitations/" + id + "/approve", director, null).getBody())
                .contains("INVITATION_REQUEST_EXPIRED");
        assertThat(post("/api/portal/staff/invitations/" + id + "/reject", director, "{\"reason\":\"Lapsed.\"}")
                .getStatusCode()).as("a lapsed request can still be closed").isEqualTo(HttpStatus.OK);
    }

    @Test
    void aBranchManagerSeesOnlyTheirOwnBranchesInvitations() {
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        String toHarmony = fresh();
        invite(director, toHarmony, "sales_manager", harmony);
        String ours = fresh();
        requestInvite(manager, ours, "sales_manager", null);

        assertThat(restTemplate.exchange("/api/portal/staff/invitations", HttpMethod.GET, entity(manager, null), String.class)
                .getBody()).contains(ours).doesNotContain(toHarmony);
        assertThat(restTemplate.exchange("/api/portal/staff/invitations", HttpMethod.GET, entity(director, null), String.class)
                .getBody()).contains(ours).contains(toHarmony);
    }

    // --- managing the staff a company has ---

    @Test
    void theStaffListShowsTheCompanyAndABranchManagerOnlyTheirBranch() {
        staffToken(tenantId, "sales_manager", harmony);
        String harmonyEmail = lastStaffEmail;
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        String managerEmail = lastStaffEmail;

        String all = restTemplate.exchange("/api/portal/staff", HttpMethod.GET, entity(director, null), String.class).getBody();
        assertThat(all).contains(directorEmail).contains(harmonyEmail).contains(managerEmail)
                .contains("\"branchName\":\"Harmony\"");
        String branch = restTemplate.exchange("/api/portal/staff", HttpMethod.GET, entity(manager, null), String.class).getBody();
        assertThat(branch).contains(managerEmail).doesNotContain(harmonyEmail).doesNotContain(directorEmail);

        String otherCompany = staffToken(tenant(), "executive_director", null);
        assertThat(restTemplate.exchange("/api/portal/staff", HttpMethod.GET, entity(otherCompany, null), String.class)
                .getBody()).doesNotContain(directorEmail);
    }

    /** One role replaces all — including a leftover buyer role — and the person's sessions end. */
    @Test
    void changingARoleReplacesEveryAssignmentAndEndsTheirSessions() {
        staffToken(tenantId, "branch_manager", doubleKing);
        String email = lastStaffEmail;
        UUID userId = userIdOf(email);
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id) SELECT gen_random_uuid(), now(), false, '"
                + userId + "', id FROM roles WHERE code = 'buyer'");

        ResponseEntity<String> changed = changeRole(userId, "sales_manager", harmony);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT string_agg(r.code || ':' || coalesce(ur.scoped_branch_id::text, 'company'), ',') "
                + "FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = '" + userId + "'"))
                .isEqualTo("sales_manager:" + harmony);
        assertThat(queryString("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + userId + "' AND revoked_at IS NULL"))
                .isEqualTo("0");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'staff.role_changed' AND target_id = '"
                + userId + "'")).contains("branch_manager in branch 'Double King'").contains("buyer").contains("sales_manager");

        assertThat(changeRole(userId, "sales_manager", harmony).getStatusCode()).as("same role again: a no-op").isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'staff.role_changed' AND target_id = '"
                + userId + "'")).isEqualTo("1");
        assertThat(changeRole(userId, "branch_manager", doubleKing).getStatusCode())
                .as("a role once held can be given back").isEqualTo(HttpStatus.OK);
    }

    @Test
    void roleChangesFollowTheSameRulesAsInvitations() {
        staffToken(tenantId, "sales_manager", null);
        UUID userId = userIdOf(lastStaffEmail);

        assertThat(changeRole(userId, "branch_manager", null).getBody()).contains("ROLE_SCOPE_MISMATCH");
        assertThat(changeRole(userId, "buyer", null).getBody()).contains("ROLE_NOT_INVITABLE");
        UUID theirs = createBranch(staffToken(tenant(), "executive_director", null), "Theirs");
        assertThat(changeRole(userId, "sales_manager", theirs).getBody()).contains("BRANCH_NOT_FOUND");
        assertThat(changeRole(userIdOf(directorEmail), "sales_manager", null).getBody()).contains("CANNOT_MANAGE_YOURSELF");

        String otherDirector = staffToken(tenant(), "executive_director", null);
        assertThat(restTemplate.exchange("/api/portal/staff/" + userId + "/role", HttpMethod.PUT,
                entity(otherDirector, "{\"roleCode\":\"legal_officer\"}"), String.class).getStatusCode())
                .as("another company's staff don't exist to you").isEqualTo(HttpStatus.NOT_FOUND);
        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        assertThat(restTemplate.exchange("/api/portal/staff/" + userId + "/role", HttpMethod.PUT,
                entity(manager, "{\"roleCode\":\"legal_officer\"}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String narrowed = staffToken(tenantId, "executive_director", doubleKing);
        assertThat(restTemplate.exchange("/api/portal/staff/" + userId + "/role", HttpMethod.PUT,
                entity(narrowed, "{\"roleCode\":\"legal_officer\"}"), String.class).getBody())
                .contains("STAFF_MANAGEMENT_REQUIRES_COMPANY_WIDE_SCOPE");
    }

    /** SI-4 the other way round: you can't touch someone who holds what you don't. */
    @Test
    void nobodyCanManageSomeoneHoldingPermissionsTheyLack() {
        staffToken(tenantId, "legal_officer", null);
        UUID userId = userIdOf(lastStaffEmail);
        execute("INSERT INTO role_permissions (id, created_at, created_by, deleted, role_id, permission_id) "
                + "SELECT gen_random_uuid(), now(), 'test', false, r.id, p.id FROM roles r, permissions p "
                + "WHERE r.code = 'legal_officer' AND p.code = 'admin.audit.view'");
        try {
            assertThat(changeRole(userId, "sales_manager", null).getBody()).contains("CANNOT_MANAGE_STAFF_MEMBER");
            assertThat(deactivate(userId).getBody()).contains("CANNOT_MANAGE_STAFF_MEMBER");
        } finally {
            execute("DELETE FROM role_permissions WHERE created_by = 'test'");
        }
    }

    @Test
    void aDeactivatedPersonCannotSignInUntilReactivated() {
        staffToken(tenantId, "sales_manager", null);
        String email = lastStaffEmail;
        UUID userId = userIdOf(email);

        assertThat(deactivate(userId).getBody()).contains("\"status\":\"deactivated\"");
        assertThat(restTemplate.postForEntity("/api/auth/login", new LoginRequest(email, PASSWORD), String.class).getBody())
                .contains("ACCOUNT_DEACTIVATED");
        assertThat(queryString("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + userId + "' AND revoked_at IS NULL"))
                .isEqualTo("0");
        assertThat(deactivate(userId).getBody()).contains("STAFF_ALREADY_DEACTIVATED");
        assertThat(post("/api/portal/staff/" + userId + "/deactivate", director, "{\"reason\":\"\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(post("/api/portal/staff/" + userId + "/reactivate", director, null).getBody()).contains("\"status\":\"active\"");
        assertThat(login(email)).isNotBlank();
        assertThat(post("/api/portal/staff/" + userId + "/reactivate", director, null).getBody()).contains("STAFF_NOT_DEACTIVATED");
    }

    /** A company must always keep someone who can manage it. */
    @Test
    void theLastActiveExecutiveDirectorCannotBeRemovedOrDemoted() {
        staffToken(tenantId, "executive_director", null);
        String secondEmail = lastStaffEmail;
        UUID second = userIdOf(secondEmail);
        execute("UPDATE users SET status = 'ACTIVE' WHERE id = '" + second + "'");

        // Two active directors: either may step the other down.
        assertThat(deactivate(second).getStatusCode()).isEqualTo(HttpStatus.OK);
        post("/api/portal/staff/" + second + "/reactivate", director, null);

        // If the caller isn't an active director themselves, the target is the last one.
        execute("UPDATE users SET status = 'PENDING_VERIFICATION' WHERE lower(email) = lower('" + directorEmail + "')");
        assertThat(deactivate(second).getBody()).contains("LAST_EXECUTIVE_DIRECTOR");
        assertThat(changeRole(second, "sales_manager", null).getBody()).contains("LAST_EXECUTIVE_DIRECTOR");
        assertThat(changeRole(second, "executive_director", null).getStatusCode())
                .as("staying a company-wide director is fine").isEqualTo(HttpStatus.OK);
    }

    // --- the roles a company can assign ---

    @Test
    void theRolesListShowsEveryTenantRoleAndWhatTheCallerCouldGrant() {
        String all = restTemplate.exchange("/api/portal/roles", HttpMethod.GET, entity(director, null), String.class).getBody();
        assertThat(all).contains("\"code\":\"executive_director\"").contains("\"scope\":\"company\"")
                .contains("\"code\":\"branch_manager\"").contains("\"scope\":\"branch\"")
                .contains("\"code\":\"surveyor_project_manager\"").contains("portal.estates.manage")
                .contains("\"custom\":false")
                .as("buyer and platform roles are never assignable").doesNotContain("\"code\":\"buyer\"")
                .doesNotContain("\"code\":\"super_admin\"")
                .as("the director holds everything").doesNotContain("\"canGrant\":false");

        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        String theirs = restTemplate.exchange("/api/portal/roles", HttpMethod.GET, entity(manager, null), String.class).getBody();
        assertThat(roleGrant(theirs, "sales_manager")).isTrue();
        assertThat(roleGrant(theirs, "surveyor_project_manager")).as("lacks portal.estates.manage").isFalse();
        assertThat(roleGrant(theirs, "executive_director")).isFalse();

        String buyer = "buyer+" + UUID.randomUUID() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Ada", "Buyer", buyer, "+2348000000000", PASSWORD, "NG", Currency.NGN), String.class);
        assertThat(restTemplate.exchange("/api/portal/roles", HttpMethod.GET, entity(login(buyer), null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static boolean roleGrant(String body, String code) {
        String from = body.substring(body.indexOf("\"code\":\"" + code + "\""));
        String grant = from.substring(from.indexOf("\"canGrant\":") + 11);
        return grant.startsWith("true");
    }

    // --- fixtures ---

    private ResponseEntity<String> invite(String token, String email, String role, UUID branch) {
        String body = "{\"email\":\"" + email + "\",\"firstName\":\"Test\",\"lastName\":\"Staff\",\"roleCode\":\""
                + role + "\"" + (branch == null ? "" : ",\"branchId\":\"" + branch + "\"") + "}";
        return restTemplate.exchange("/api/portal/staff/invitations", HttpMethod.POST, entity(token, body), String.class);
    }

    private ResponseEntity<String> preview(String token) {
        return post("/api/auth/invitations/preview", null, "{\"token\":\"" + token + "\"}");
    }

    private ResponseEntity<String> revoke(UUID id) {
        return post("/api/portal/staff/invitations/" + id + "/revoke", director, null);
    }

    private ResponseEntity<String> resend(UUID id) {
        return post("/api/portal/staff/invitations/" + id + "/resend", director, null);
    }

    private ResponseEntity<String> post(String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private List<UUID> invitationIds() {
        return restTemplate.exchange("/api/portal/staff/invitations", HttpMethod.GET, entity(director, null),
                        new ParameterizedTypeReference<List<StaffInvitationDto>>() {
                        }).getBody().stream().map(StaffInvitationDto::id).toList();
    }

    private String lastLink() {
        List<String> links = links();
        assertThat(links).as("an invitation was delivered").isNotEmpty();
        String message = links.getLast();
        return message.substring(message.indexOf("link=") + 5).trim();
    }

    private ResponseEntity<String> changeRole(UUID userId, String role, UUID branch) {
        String body = "{\"roleCode\":\"" + role + "\"" + (branch == null ? "" : ",\"branchId\":\"" + branch + "\"") + "}";
        return restTemplate.exchange("/api/portal/staff/" + userId + "/role", HttpMethod.PUT, entity(director, body), String.class);
    }

    private ResponseEntity<String> deactivate(UUID userId) {
        return post("/api/portal/staff/" + userId + "/deactivate", director, "{\"reason\":\"Left the company.\"}");
    }

    private static UUID userIdOf(String email) {
        return UUID.fromString(queryString("SELECT id FROM users WHERE lower(email) = lower('" + email + "')"));
    }

    private List<String> links() {
        return deliveries.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains(" link=")).toList();
    }

    private List<String> approvalNotices() {
        return deliveries.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("[TEST-ONLY approval request]")).toList();
    }

    private ResponseEntity<String> requestInvite(String token, String email, String role, UUID branch) {
        String body = "{\"email\":\"" + email + "\",\"firstName\":\"Test\",\"lastName\":\"Staff\",\"roleCode\":\""
                + role + "\"" + (branch == null ? "" : ",\"branchId\":\"" + branch + "\"") + "}";
        return restTemplate.exchange("/api/portal/staff/invitations/requests", HttpMethod.POST, entity(token, body), String.class);
    }

    private static UUID idOf(ResponseEntity<String> response) {
        String body = response.getBody();
        int at = body.indexOf("\"id\":\"") + 6;
        return UUID.fromString(body.substring(at, at + 36));
    }

    private String lastToken() {
        String link = lastLink();
        return link.substring(link.indexOf("#token=") + 7);
    }

    private static String fresh() {
        return "staff+" + UUID.randomUUID() + "@example.com";
    }

    private UUID createBranch(String token, String name) {
        ResponseEntity<String> created = restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(token, "{\"name\":\"" + name + "\"}"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = created.getBody();
        int at = body.indexOf("\"id\":\"") + 6;
        return UUID.fromString(body.substring(at, at + 36));
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
        lastStaffEmail = email;
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
