package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.TitleDocumentDto;
import com.techcomfort.landvaultbackend.inventory.dto.TitleReviewDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceListingDto;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.testsupport.EstatePhotoFixture;
import com.techcomfort.landvaultbackend.testsupport.TestImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Developers declare titles; HomeTrue verifies them. Real uploads to RustFS's
 * private bucket, the review queue, the stale-submission guard, and what the
 * marketplace shows. Wired to the restricted app role so the row security on
 * the title tables is what both the portal and the reviewer run under.
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * See AGENTS.md, "Estate titles".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class EstateTitlesUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_titles_it";
    private static final String APP_ROLE_PASSWORD = "titles-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";
    private static final String ACCESS_KEY = "titles-access-key";
    private static final String SECRET_KEY = "titles-secret-key-123";
    private static final String PUBLIC_BUCKET = "titles-public";
    private static final String PRIVATE_BUCKET = "titles-private";
    private static final byte[] PDF = "%PDF-1.4\n% owner: Mrs A. Bello, 4 Unity Close\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final AtomicInteger PATCH = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.1"))
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withExposedPorts(9000)
            .waitingFor(Wait.forListeningPort());

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
        registry.add("landvault.storage.endpoint", EstateTitlesUnderRlsIT::endpoint);
        registry.add("landvault.storage.access-key", () -> ACCESS_KEY);
        registry.add("landvault.storage.secret-key", () -> SECRET_KEY);
        registry.add("landvault.storage.public-bucket", () -> PUBLIC_BUCKET);
        registry.add("landvault.storage.private-bucket", () -> PRIVATE_BUCKET);
        registry.add("landvault.storage.public-base-url", () -> endpoint() + "/" + PUBLIC_BUCKET);
    }

    private static String endpoint() {
        return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
    }

    private static S3Client s3;

    @BeforeAll
    static void createBuckets() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
        s3.createBucket(r -> r.bucket(PUBLIC_BUCKET));
        s3.createBucket(r -> r.bucket(PRIVATE_BUCKET));
    }

    @AfterAll
    static void closeS3() {
        s3.close();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID tenantId;
    private String token;
    private String adminToken;
    private double x;
    private double y;

    @BeforeEach
    void setUp() {
        int n = PATCH.getAndIncrement();
        x = 7.250 + 0.030 * (n % 8);
        y = 8.850 + 0.030 * (n / 8);
        adminToken = login(ADMIN_EMAIL);
        tenantId = tenant();
        token = staffToken(tenantId, "executive_director", null);
    }

    // ------------------------------------------------------------ declare, review, publish

    @Test
    void aDeclaredTitleIsPendingUntilHomeTrueVerifiesItAndOnlyThenReadsVerifiedToBuyers() throws Exception {
        UUID estate = listableEstate("Titled Gardens");
        assertThat(code(restTemplate.exchange(titlePath(estate, ""), HttpMethod.GET, entity(token, null), String.class)))
                .isEqualTo("TITLE_NOT_RECORDED");

        UUID titleDoc = upload(token, estate, "title_document", "c-of-o.pdf", "application/pdf", PDF);
        UUID surveyPlan = upload(token, estate, "survey_plan", "survey.jpg", "image/jpeg", TestImages.jpeg());
        assertThat(queryString("SELECT visibility FROM stored_files WHERE id = '" + titleDoc + "'"))
                .as("owners' names and addresses never go public").isEqualTo("PRIVATE");

        EstateTitleDto submitted = submit(token, estate, titleDoc, surveyPlan, null, "2019-05-14").getBody();
        assertThat(submitted.status()).isEqualTo("pending");
        assertThat(submitted.titleType()).isEqualTo("C of O");
        assertThat(submitted.documents()).extracting(TitleDocumentDto::kind).containsExactly("title_document", "survey_plan");
        assertThat(submitted.decidedAt()).isNull();
        assertThat(checkStatus(estate)).as("the title check waits on HomeTrue").isEqualTo("PENDING");

        assertThat(publish(estate).getStatusCode()).isEqualTo(HttpStatus.OK);
        MarketplaceListingDto declared = listing(estate);
        assertThat(declared.titleType()).isEqualTo("C of O");
        assertThat(declared.titleStatus()).as("a type is never shown without its status").isEqualTo("pending");
        assertThat(declared.titleVerifiedAt()).isNull();
        assertThat(declared.lastVerifiedDate()).as("nobody has checked anything").isNull();

        TitleReviewDto queued = pending().stream().filter(t -> t.estateId().equals(estate)).findFirst().orElseThrow();
        assertThat(queued.companyName()).startsWith("Title Co ");
        assertThat(queued.estateName()).startsWith("Titled Gardens");
        assertThat(queued.estateState()).isEqualTo("Federal Capital Territory (Abuja)");

        DownloadLink link = adminDownload(queued.id(), titleDoc);
        assertThat(get(link.url()).body()).isEqualTo(PDF);

        ResponseEntity<TitleReviewDto> verified = decide(queued.id(), "verify",
                "{\"verificationSource\":\"registry_search\",\"note\":\"Search report AGIS/2026/77\",\"submittedAt\":\""
                        + queued.submittedAt() + "\"}");
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().status()).isEqualTo("verified");
        assertThat(verified.getBody().verificationSource()).isEqualTo("registry_search");

        MarketplaceListingDto checked = listing(estate);
        assertThat(checked.titleStatus()).isEqualTo("verified");
        assertThat(checked.titleVerifiedAt()).isNotNull();
        assertThat(checked.lastVerifiedDate()).isNotNull();
        assertThat(checked.verificationChecks()).singleElement().satisfies(c -> {
            assertThat(c.checkType()).isEqualTo("title_verification");
            assertThat(c.status()).isEqualTo("verified");
            assertThat(c.verificationSource()).isEqualTo("registry_search");
        });
        String raw = restTemplate.getForObject("/api/marketplace/estates/" + estate, String.class);
        assertThat(raw).as("the number, files and note stay private")
                .doesNotContain("FCT/ABU").doesNotContain(titleDoc.toString()).doesNotContain("AGIS/2026/77");

        EstateDetailDto detail = restTemplate.exchange("/api/portal/estates/" + estate, HttpMethod.GET,
                entity(token, null), EstateDetailDto.class).getBody();
        assertThat(detail.title().status()).isEqualTo("verified");
        assertThat(detail.title().decisionNote()).isEqualTo("Search report AGIS/2026/77");

        ResponseEntity<String> again = decide(queued.id(), "verify", "{\"verificationSource\":\"manual_review\",\"submittedAt\":\""
                + queued.submittedAt() + "\"}", String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("TITLE_NOT_PENDING");
    }

    @Test
    void resubmittingAVerifiedTitleClearsTheVerificationAtOnce() throws Exception {
        UUID estate = listableEstate("Changed Gardens");
        UUID titleDoc = upload(token, estate, "title_document", "a.pdf", "application/pdf", PDF);
        UUID surveyPlan = upload(token, estate, "survey_plan", "b.pdf", "application/pdf", PDF);
        submit(token, estate, titleDoc, surveyPlan, null, null);
        assertThat(publish(estate).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(estate);
        assertThat(listing(estate).titleStatus()).isEqualTo("verified");

        UUID newTitleDoc = upload(token, estate, "title_document", "c.pdf", "application/pdf", PDF);
        EstateTitleDto resubmitted = submit(token, estate, newTitleDoc, surveyPlan, null, null).getBody();

        assertThat(resubmitted.status()).isEqualTo("pending");
        assertThat(resubmitted.decidedAt()).isNull();
        assertThat(resubmitted.verificationSource()).isNull();
        assertThat(resubmitted.decisionNote()).isNull();
        MarketplaceListingDto listing = listing(estate);
        assertThat(listing.titleStatus()).isEqualTo("pending");
        assertThat(listing.titleVerifiedAt()).isNull();
        assertThat(listing.lastVerifiedDate()).as("no \"land checked\" for documents nobody checked").isNull();
        assertThat(checkStatus(estate)).isEqualTo("PENDING");
    }

    @Test
    void aDecisionOnASubmissionTheDeveloperHasSinceReplacedIsRefused() throws Exception {
        UUID estate = estate("Swapped Gardens");
        UUID titleDoc = upload(token, estate, "title_document", "a.pdf", "application/pdf", PDF);
        UUID surveyPlan = upload(token, estate, "survey_plan", "b.pdf", "application/pdf", PDF);
        submit(token, estate, titleDoc, surveyPlan, null, null);
        TitleReviewDto seen = titleOf(estate);

        UUID swapped = upload(token, estate, "title_document", "swapped.pdf", "application/pdf", PDF);
        submit(token, estate, swapped, surveyPlan, null, null);

        ResponseEntity<String> stale = decide(seen.id(), "verify",
                "{\"verificationSource\":\"manual_review\",\"submittedAt\":\"" + seen.submittedAt() + "\"}", String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(stale)).isEqualTo("TITLE_CHANGED");
        assertThat(titleOf(estate).status()).as("nothing was decided").isEqualTo("pending");

        ResponseEntity<String> missing = decide(seen.id(), "verify", "{\"verificationSource\":\"manual_review\"}", String.class);
        assertThat(missing.getStatusCode()).as("the submission reviewed must be named").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aRejectionNeedsANoteTheDeveloperSees() throws Exception {
        UUID estate = estate("Rejected Gardens");
        UUID titleDoc = upload(token, estate, "title_document", "a.pdf", "application/pdf", PDF);
        UUID surveyPlan = upload(token, estate, "survey_plan", "b.pdf", "application/pdf", PDF);
        submit(token, estate, titleDoc, surveyPlan, null, null);
        TitleReviewDto seen = titleOf(estate);

        assertThat(decide(seen.id(), "reject", "{\"note\":\" \",\"submittedAt\":\"" + seen.submittedAt() + "\"}",
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<TitleReviewDto> rejected = decide(seen.id(), "reject",
                "{\"note\":\"The survey plan is for another parcel.\",\"submittedAt\":\"" + seen.submittedAt() + "\"}");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);

        EstateTitleDto mine = restTemplate.exchange(titlePath(estate, ""), HttpMethod.GET, entity(token, null),
                EstateTitleDto.class).getBody();
        assertThat(mine.status()).isEqualTo("rejected");
        assertThat(mine.decisionNote()).isEqualTo("The survey plan is for another parcel.");
        assertThat(checkStatus(estate)).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.title_rejected' AND target_id = '"
                + estate + "'")).isOne();
    }

    @Test
    void aPersonCannotClaimARegistryIntegrationThatDoesNotExist() throws Exception {
        UUID estate = estate("Claimed Gardens");
        submit(token, estate, upload(token, estate, "title_document", "a.pdf", "application/pdf", PDF),
                upload(token, estate, "survey_plan", "b.pdf", "application/pdf", PDF), null, null);
        TitleReviewDto seen = titleOf(estate);

        ResponseEntity<String> agis = decide(seen.id(), "verify",
                "{\"verificationSource\":\"agis\",\"submittedAt\":\"" + seen.submittedAt() + "\"}", String.class);
        assertThat(agis.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(agis)).isEqualTo("VERIFICATION_SOURCE_NOT_ALLOWED");
    }

    // ------------------------------------------------------------ the submission's rules

    @Test
    void aSubmissionMustNameThisEstatesConfirmedDocumentsOfTheRightKind() throws Exception {
        UUID estate = estate("Strict Gardens");
        UUID other = estate("Other Gardens");
        UUID titleDoc = upload(token, estate, "title_document", "a.pdf", "application/pdf", PDF);
        UUID surveyPlan = upload(token, estate, "survey_plan", "b.pdf", "application/pdf", PDF);
        UUID othersPlan = upload(token, other, "survey_plan", "c.pdf", "application/pdf", PDF);
        UploadTicket unconfirmed = requestUpload(token, estate, "title_document", "d.pdf", "application/pdf", PDF.length);

        assertThat(code(submitRaw(estate, null, surveyPlan, null))).isEqualTo("TITLE_DOCUMENT_REQUIRED");
        assertThat(code(submitRaw(estate, titleDoc, null, null))).isEqualTo("SURVEY_PLAN_REQUIRED");
        assertThat(code(submitRaw(estate, surveyPlan, titleDoc, null))).as("wrong kinds")
                .isEqualTo("TITLE_DOCUMENT_NOT_FOUND");
        assertThat(code(submitRaw(estate, titleDoc, othersPlan, null))).as("another estate's")
                .isEqualTo("TITLE_DOCUMENT_NOT_FOUND");
        assertThat(code(submitRaw(estate, unconfirmed.fileId(), surveyPlan, null))).as("not confirmed")
                .isEqualTo("TITLE_DOCUMENT_NOT_FOUND");
        assertThat(code(submitRaw(estate, titleDoc, surveyPlan, LocalDate.now().plusDays(3).toString())))
                .isEqualTo("ISSUED_DATE_IN_FUTURE");
        assertThat(count("SELECT count(*) FROM estate_titles WHERE estate_id = '" + estate + "'")).isZero();
    }

    @Test
    void anotherCompanySeesNeitherTheTitleNorItsDocuments() throws Exception {
        UUID estate = estate("Walled Gardens");
        UUID titleDoc = upload(token, estate, "title_document", "a.pdf", "application/pdf", PDF);
        submit(token, estate, titleDoc, upload(token, estate, "survey_plan", "b.pdf", "application/pdf", PDF), null, null);
        String rival = staffToken(tenant(), "executive_director", null);

        assertThat(restTemplate.exchange(titlePath(estate, ""), HttpMethod.GET, entity(rival, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange(titlePath(estate, "/documents/" + titleDoc + "/download"), HttpMethod.GET,
                entity(rival, null), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange(titlePath(estate, "/documents/" + titleDoc + "/download"), HttpMethod.GET,
                entity(token, null), DownloadLink.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void branchStaffCannotDeclareTheTitleOfACompanyLevelEstate() {
        UUID estate = estate("Company Gardens");
        UUID branch = branch();
        String branchSurveyor = staffToken(tenantId, "surveyor_project_manager", branch);

        ResponseEntity<String> refused = restTemplate.exchange(titlePath(estate, "/documents/uploads"), HttpMethod.POST,
                entity(branchSurveyor, uploadBody("title_document", "a.pdf", "application/pdf", PDF.length)), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(refused)).isEqualTo("ESTATE_READ_ONLY_FOR_BRANCH");
    }

    // ------------------------------------------------------------ who may decide

    @Test
    void developersCanNoLongerVouchForTheirOwnLand() {
        UUID estate = estate("Self Gardens");

        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/verification-checks", HttpMethod.POST,
                entity(token, "{\"checkType\":\"title_verification\",\"status\":\"verified\",\"verificationSource\":\"manual_review\"}"),
                String.class).getStatusCode().is2xxSuccessful()).as("the portal route is gone").isFalse();
        assertThat(restTemplate.exchange("/api/admin/title-reviews", HttpMethod.GET, entity(token, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/admin/estates/" + estate + "/verification-checks", HttpMethod.POST,
                entity(token, "{\"checkType\":\"encroachment_status\"}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM estate_verification_checks WHERE estate_id = '" + estate + "'")).isZero();
    }

    @Test
    void homeTrueRecordsTheOtherChecksButTheTitleCheckFollowsTheReview() {
        UUID estate = estate("Checked Gardens");
        String path = "/api/admin/estates/" + estate + "/verification-checks";

        ResponseEntity<String> encroachment = restTemplate.exchange(path, HttpMethod.POST, entity(adminToken,
                "{\"checkType\":\"encroachment_status\",\"status\":\"verified\",\"verificationSource\":\"manual_review\"}"),
                String.class);
        assertThat(encroachment.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(code(restTemplate.exchange(path, HttpMethod.POST, entity(adminToken,
                "{\"checkType\":\"title_verification\",\"status\":\"verified\",\"verificationSource\":\"manual_review\"}"),
                String.class))).isEqualTo("TITLE_CHECK_FOLLOWS_REVIEW");
        assertThat(code(restTemplate.exchange(path, HttpMethod.POST, entity(adminToken,
                "{\"checkType\":\"agis_registration\",\"status\":\"verified\",\"verificationSource\":\"agis\"}"),
                String.class))).isEqualTo("VERIFICATION_SOURCE_NOT_ALLOWED");
        assertThat(restTemplate.exchange(path, HttpMethod.POST, entity(adminToken,
                "{\"checkType\":\"agis_registration\",\"status\":\"verified\"}"), String.class).getStatusCode())
                .as("a verified check says where it came from").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theReviewerOpensOnlyTheDocumentsOfTheSubmissionUnderReview() throws Exception {
        UUID estate = estate("Scoped Gardens");
        UUID titleDoc = upload(token, estate, "title_document", "a.pdf", "application/pdf", PDF);
        UUID surveyPlan = upload(token, estate, "survey_plan", "b.pdf", "application/pdf", PDF);
        UUID unattached = upload(token, estate, "deed", "c.pdf", "application/pdf", PDF);
        submit(token, estate, titleDoc, surveyPlan, null, null);
        UUID titleId = titleOf(estate).id();

        assertThat(restTemplate.exchange("/api/admin/title-reviews/" + titleId + "/documents/" + unattached + "/download",
                HttpMethod.GET, entity(adminToken, null), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theAppRoleCannotHardDeleteTitleEvidence() {
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'estate_title_documents', 'DELETE')"))
                .isEqualTo("f");
    }

    // ============================================================ helpers

    private void verify(UUID estate) {
        TitleReviewDto seen = titleOf(estate);
        assertThat(decide(seen.id(), "verify", "{\"verificationSource\":\"manual_review\",\"submittedAt\":\""
                + seen.submittedAt() + "\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private TitleReviewDto titleOf(UUID estate) {
        for (String status : List.of("pending", "verified", "rejected")) {
            ResponseEntity<List<TitleReviewDto>> response = restTemplate.exchange("/api/admin/title-reviews?status=" + status,
                    HttpMethod.GET, entity(adminToken, null), new ParameterizedTypeReference<>() {
                    });
            for (TitleReviewDto t : response.getBody()) {
                if (t.estateId().equals(estate)) {
                    return t;
                }
            }
        }
        throw new AssertionError("no title for " + estate);
    }

    private List<TitleReviewDto> pending() {
        ResponseEntity<List<TitleReviewDto>> response = restTemplate.exchange("/api/admin/title-reviews",
                HttpMethod.GET, entity(adminToken, null), new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<TitleReviewDto> decide(UUID titleId, String action, String body) {
        return decide(titleId, action, body, TitleReviewDto.class);
    }

    private <T> ResponseEntity<T> decide(UUID titleId, String action, String body, Class<T> type) {
        return restTemplate.exchange("/api/admin/title-reviews/" + titleId + "/" + action, HttpMethod.POST,
                entity(adminToken, body), type);
    }

    private DownloadLink adminDownload(UUID titleId, UUID fileId) {
        ResponseEntity<DownloadLink> link = restTemplate.exchange(
                "/api/admin/title-reviews/" + titleId + "/documents/" + fileId + "/download",
                HttpMethod.GET, entity(adminToken, null), DownloadLink.class);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.OK);
        return link.getBody();
    }

    private ResponseEntity<EstateTitleDto> submit(String asToken, UUID estate, UUID titleDoc, UUID surveyPlan,
                                                  UUID deed, String issued) {
        ResponseEntity<EstateTitleDto> response = restTemplate.exchange(titlePath(estate, ""), HttpMethod.PUT,
                entity(asToken, submitBody(titleDoc, surveyPlan, deed, issued)), EstateTitleDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private ResponseEntity<String> submitRaw(UUID estate, UUID titleDoc, UUID surveyPlan, String issued) {
        return restTemplate.exchange(titlePath(estate, ""), HttpMethod.PUT,
                entity(token, submitBody(titleDoc, surveyPlan, null, issued)), String.class);
    }

    private static String submitBody(UUID titleDoc, UUID surveyPlan, UUID deed, String issued) {
        String body = "{\"titleType\":\"C of O\",\"titleNumber\":\"FCT/ABU/2019/1234\""
                + (issued == null ? "" : ",\"issuedDate\":\"" + issued + "\"")
                + (titleDoc == null ? "" : ",\"titleDocumentFileId\":\"" + titleDoc + "\"")
                + (surveyPlan == null ? "" : ",\"surveyPlanFileId\":\"" + surveyPlan + "\"")
                + (deed == null ? "" : ",\"deedFileId\":\"" + deed + "\"") + "}";
        return body;
    }

    private UUID upload(String asToken, UUID estate, String kind, String name, String type, byte[] bytes) throws Exception {
        UploadTicket ticket = requestUpload(asToken, estate, kind, name, type, bytes.length);
        assertThat(ticket.uploadUrl()).contains(PRIVATE_BUCKET);
        assertThat(put(ticket, bytes)).isEqualTo(200);
        ResponseEntity<TitleDocumentDto> confirmed = restTemplate.exchange(
                titlePath(estate, "/documents/" + ticket.fileId() + "/confirm"), HttpMethod.POST,
                entity(asToken, null), TitleDocumentDto.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(confirmed.getBody().kind()).isEqualTo(kind);
        return ticket.fileId();
    }

    private UploadTicket requestUpload(String asToken, UUID estate, String kind, String name, String type, long size) {
        ResponseEntity<UploadTicket> ticket = restTemplate.exchange(titlePath(estate, "/documents/uploads"),
                HttpMethod.POST, entity(asToken, uploadBody(kind, name, type, size)), UploadTicket.class);
        assertThat(ticket.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ticket.getBody();
    }

    private static String uploadBody(String kind, String name, String type, long size) {
        return "{\"fileName\":\"" + name + "\",\"contentType\":\"" + type + "\",\"sizeBytes\":" + size
                + ",\"kind\":\"" + kind + "\"}";
    }

    private int put(UploadTicket ticket, byte[] bytes) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        ticket.headers().forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpResponse<byte[]> get(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private MarketplaceListingDto listing(UUID estate) {
        ResponseEntity<MarketplaceListingDto> response = restTemplate.getForEntity("/api/marketplace/estates/" + estate,
                MarketplaceListingDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String checkStatus(UUID estate) {
        return queryString("SELECT status FROM estate_verification_checks WHERE check_type = 'TITLE_VERIFICATION' "
                + "AND estate_id = '" + estate + "'");
    }

    private static String code(ResponseEntity<?> response) {
        return code(String.valueOf(response.getBody()));
    }

    private static String code(String body) {
        int at = body.indexOf("\"code\":\"");
        return at < 0 ? body : body.substring(at + 8, body.indexOf('"', at + 8));
    }

    private ResponseEntity<String> publish(UUID estate) {
        return restTemplate.exchange("/api/portal/estates/" + estate + "/publish", HttpMethod.POST, entity(token, ""), String.class);
    }

    private static String titlePath(UUID estate, String rest) {
        return "/api/portal/estates/" + estate + "/title" + rest;
    }

    private UUID estate(String name) {
        String body = "{\"name\":\"" + name + " " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"state\":\"FCT\",\"footprint\":" + rect(x, y, 0.010, 0.010) + "}";
        x += 0.012;
        ResponseEntity<String> created = restTemplate.exchange("/api/portal/estates", HttpMethod.POST, entity(token, body), String.class);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        return idOf(created);
    }

    /** Meets every publication condition: verified and entitled company, boundary, a plot, fees, refund terms, a photo. */
    private UUID listableEstate(String name) {
        execute("UPDATE organizations SET verification_state = 'VERIFIED', marketplace_publishing = true WHERE id = '"
                + tenantId + "'");
        UUID estate = estate(name);
        ResponseEntity<String> tier = restTemplate.exchange("/api/portal/estates/" + estate + "/price-tiers", HttpMethod.POST,
                entity(token, "{\"tierType\":\"LAND_SIZE\",\"sizeSqm\":500,\"price\":1000000,\"currency\":\"NGN\",\"label\":\"T\"}"),
                String.class);
        assertThat(tier.getStatusCode()).as(tier.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> plot = restTemplate.exchange("/api/portal/estates/" + estate + "/plots", HttpMethod.POST,
                entity(token, "{\"plots\":[{\"plotNumber\":\"1\",\"priceTierId\":\"" + idOf(tier)
                        + "\",\"status\":\"available-dev\"}]}"), String.class);
        assertThat(plot.getStatusCode()).as(plot.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/fees", HttpMethod.PUT,
                entity(token, "{\"fees\":[]}"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/refund-terms", HttpMethod.PUT,
                entity(token, "{\"deductionPct\":20.00,\"processingDays\":90,\"appliesTo\":\"total_price\"}"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        execute(EstatePhotoFixture.coverPhotoSql(estate));
        return estate;
    }

    private UUID branch() {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('" + id + "', now(), false, '"
                + tenantId + "', 'Branch " + id.toString().substring(0, 6) + "')");
        return id;
    }

    private static UUID idOf(ResponseEntity<String> response) {
        String body = response.getBody();
        int at = body.indexOf("\"id\":\"") + 6;
        return UUID.fromString(body.substring(at, at + 36));
    }

    private static String rect(double lng, double lat, double width, double height) {
        return String.format(Locale.ROOT, "{\"type\":\"Polygon\",\"coordinates\":[[[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f]]]}",
                lng, lat, lng + width, lat, lng + width, lat + height, lng, lat + height, lng, lat);
    }

    private UUID tenant() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Title Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken, request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String staffToken(UUID owningTenantId, String roleCode, UUID branchId) {
        String email = "staff+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, " + (branchId == null ? "NULL" : "'" + branchId + "'")
                + " FROM users u, roles r WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "'");
        return login(email);
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
        return Long.parseLong(queryString(sql));
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
