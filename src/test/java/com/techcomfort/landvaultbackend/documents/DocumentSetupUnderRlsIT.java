package com.techcomfort.landvaultbackend.documents;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.documents.dto.EstateLetterTermsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterApprovalDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateReviewDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplatesOverviewDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterVersionsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTermsReviewDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadOverviewDto;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateBranchRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PortalBranchDto;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.testsupport.TestImages;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Developer document setup end to end. Letterheads (DS-1, DS-2): real uploads
 * to RustFS into the private bucket, the company/branch fallback, the signatory
 * as one unit. Allocation terms (DS-3, DS-4): versions, the clause rules, the
 * clauses written from the cost disclosure. And for both: who may change what,
 * and company isolation. Wired to the restricted app role so
 * {@code letterheads}' row security is what the portal actually runs under.
 * <strong>Do not convert this class to {@code @ServiceConnection}</strong>.
 * See AGENTS.md, "Documents: letterheads" and "Documents: allocation terms".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class DocumentSetupUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_letterheads_it";
    private static final String APP_ROLE_PASSWORD = "letterheads-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";
    private static final String ACCESS_KEY = "letterheads-access-key";
    private static final String SECRET_KEY = "letterheads-secret-key-123";
    private static final String PUBLIC_BUCKET = "letterheads-public";
    private static final String PRIVATE_BUCKET = "letterheads-private";

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
        registry.add("landvault.storage.endpoint", DocumentSetupUnderRlsIT::endpoint);
        registry.add("landvault.storage.access-key", () -> ACCESS_KEY);
        registry.add("landvault.storage.secret-key", () -> SECRET_KEY);
        registry.add("landvault.storage.public-bucket", () -> PUBLIC_BUCKET);
        registry.add("landvault.storage.private-bucket", () -> PRIVATE_BUCKET);
        registry.add("landvault.storage.public-base-url", () -> endpoint() + "/" + PUBLIC_BUCKET);
    }

    private static String endpoint() {
        return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
    }

    private static S3Client admin;

    @BeforeAll
    static void createBuckets() {
        admin = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
        admin.createBucket(r -> r.bucket(PUBLIC_BUCKET));
        admin.createBucket(r -> r.bucket(PRIVATE_BUCKET));
    }

    @AfterAll
    static void closeAdmin() {
        admin.close();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID tenantId;
    private String director;

    @BeforeEach
    void setUp() {
        tenantId = tenant();
        director = staffToken(tenantId, "executive_director", null);
    }

    // ------------------------------------------------------------ the company's letterhead

    @Test
    void aLetterIsReadyOnlyOnceTheCompanyHasASignatoryAndTheirSignature() throws Exception {
        LetterheadDto empty = overview(director).company();
        assertThat(empty.version()).isZero();
        assertThat(empty.missing()).containsExactly("signatory_name", "signatory_title", "signature");
        assertThat(empty.printed().companyName()).startsWith("Letterhead Co");
        assertThat(empty.printed().address()).as("head office").isEqualTo("1 Broad Street, Abuja, FCT");

        LetterheadDto named = setSignatory(director, "company", "Adaeze Okafor", "Managing Director").getBody();
        assertThat(named.version()).isEqualTo(1);
        assertThat(named.missing()).containsExactly("signature");

        LetterheadDto signed = uploadImage(director, "company", "signature", TestImages.jpegWithMetadata(1)).getBody();
        assertThat(signed.version()).isEqualTo(2);
        assertThat(signed.missing()).isEmpty();
        assertThat(signed.printed().signatoryName()).isEqualTo("Adaeze Okafor");
        assertThat(signed.printed().signature().source()).isEqualTo("company");

        // Private, and its metadata is gone.
        assertThat(queryString("SELECT visibility FROM stored_files WHERE id = '" + signed.signature().fileId() + "'"))
                .isEqualToIgnoringCase("private");
        DownloadLink link = restTemplate.exchange("/api/portal/letterheads/files/" + signed.signature().fileId() + "/link",
                HttpMethod.GET, entity(director, null), DownloadLink.class).getBody();
        byte[] served = get(link.url()).body();
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain(TestImages.PLANTED);
    }

    @Test
    void aChangeThatChangesNothingKeepsTheVersionAndAReplacedImageIsKept() throws Exception {
        setSignatory(director, "company", "Adaeze Okafor", "Managing Director");
        LetterheadDto same = setSignatory(director, "company", "  Adaeze Okafor ", "Managing Director").getBody();
        assertThat(same.version()).as("a no-op").isEqualTo(1);

        UUID first = uploadImage(director, "company", "seal", TestImages.jpeg()).getBody().seal().fileId();
        LetterheadDto replaced = uploadImage(director, "company", "seal", TestImages.jpegWithMetadata(1)).getBody();
        assertThat(replaced.seal().fileId()).isNotEqualTo(first);
        assertThat(replaced.version()).isEqualTo(3);
        assertThat(queryString("SELECT status FROM stored_files WHERE id = '" + first + "'"))
                .as("letters already issued point at the old seal").isEqualToIgnoringCase("ready");
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE tenant_id = '" + tenantId
                + "' AND action LIKE 'letterhead.%'")).isEqualTo(3);

        LetterheadDto removed = exchange(director, HttpMethod.DELETE, "/api/portal/letterheads/company/images/seal", null);
        assertThat(removed.seal()).isNull();
        assertThat(removed.version()).isEqualTo(4);
    }

    // ------------------------------------------------------------ branches

    @Test
    void aBranchPrintsItsOwnOfficeAndOtherwiseTheCompanys() throws Exception {
        UUID branch = branch("Double King", "12 Airport Road", "+2348011111111");
        setSignatory(director, "company", "Adaeze Okafor", "Managing Director");
        uploadImage(director, "company", "signature", TestImages.jpeg());
        UUID companyLogo = uploadImage(director, "company", "logo", TestImages.jpeg()).getBody().logo().fileId();

        LetterheadDto inherited = branchLetterhead(director, branch);
        assertThat(inherited.version()).as("nothing of its own yet").isZero();
        assertThat(inherited.printed().address()).isEqualTo("12 Airport Road, Abuja, Federal Capital Territory (Abuja)");
        assertThat(inherited.printed().phone()).isEqualTo("+2348011111111");
        assertThat(inherited.printed().email()).as("not entered for the branch: head office's")
                .startsWith("org+");
        assertThat(inherited.printed().signatoryName()).isEqualTo("Adaeze Okafor");
        assertThat(inherited.printed().signatorySource()).isEqualTo("company");
        assertThat(inherited.printed().logo().fileId()).isEqualTo(companyLogo);
        assertThat(inherited.missing()).isEmpty();

        UUID branchLogo = uploadImage(director, branch.toString(), "logo", TestImages.jpeg()).getBody().logo().fileId();
        assertThat(branchLetterhead(director, branch).printed().logo().fileId()).isEqualTo(branchLogo);
        assertThat(overview(director).company().printed().logo().fileId()).as("the company's is untouched")
                .isEqualTo(companyLogo);
    }

    @Test
    void aBranchSignerIsOneUnitAndNeverBorrowsTheCompanySignature() throws Exception {
        UUID branch = branch("Heritage", null, null);
        setSignatory(director, "company", "Adaeze Okafor", "Managing Director");
        UUID companySignature = uploadImage(director, "company", "signature", TestImages.jpeg()).getBody()
                .signature().fileId();

        LetterheadDto ownSigner = setSignatory(director, branch.toString(), "Bola Ade", "Branch Manager").getBody();
        assertThat(ownSigner.printed().signatoryName()).isEqualTo("Bola Ade");
        assertThat(ownSigner.printed().signature()).as("never another person's signature under this name").isNull();
        assertThat(ownSigner.missing()).containsExactly("signature");

        LetterheadDto signed = uploadImage(director, branch.toString(), "signature", TestImages.jpeg()).getBody();
        assertThat(signed.printed().signature().fileId()).isNotEqualTo(companySignature);
        assertThat(signed.printed().signature().source()).isEqualTo("branch");
        assertThat(signed.missing()).isEmpty();

        LetterheadDto cleared = exchange(director, HttpMethod.DELETE,
                "/api/portal/letterheads/" + branch + "/signatory", null);
        assertThat(cleared.signatoryName()).isNull();
        assertThat(cleared.signature()).as("the signature goes with the signer").isNull();
        assertThat(cleared.printed().signatoryName()).isEqualTo("Adaeze Okafor");
        assertThat(cleared.printed().signature().fileId()).isEqualTo(companySignature);
    }

    // ------------------------------------------------------------ who may do what

    @Test
    void onlyACompanyWideExecutiveDirectorChangesLetterheads() {
        UUID branch = branch("Double King", null, null);
        UUID otherBranch = branch("Heritage", null, null);
        setSignatory(director, "company", "Adaeze Okafor", "Managing Director");

        String manager = staffToken(tenantId, "branch_manager", branch);
        LetterheadOverviewDto managerView = overview(manager);
        assertThat(managerView.company().printed().signatoryName()).isEqualTo("Adaeze Okafor");
        assertThat(managerView.branches()).extracting(LetterheadDto::branchId).as("their own branch only")
                .containsExactly(branch);
        assertThat(setSignatoryRaw(manager, branch.toString(), "Bola Ade", "Branch Manager").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        String finance = staffToken(tenantId, "finance_officer", null);
        assertThat(overview(finance).branches()).hasSize(2);
        assertThat(setSignatoryRaw(finance, "company", "Someone", "Else").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders narrowed = headers(director);
        narrowed.set("X-Branch-Id", otherBranch.toString());
        ResponseEntity<String> lens = restTemplate.exchange("/api/portal/letterheads/company/signatory", HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", "X", "title", "Y"), narrowed), String.class);
        assertThat(lens.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(lens.getBody()).contains("DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE");

        String buyer = buyerToken();
        assertThat(restTemplate.exchange("/api/portal/letterheads", HttpMethod.GET, entity(buyer, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherCompanyCanNeitherSeeNorUseMyLetterhead() throws Exception {
        UUID branch = branch("Double King", null, null);
        UUID signature = uploadImage(director, "company", "signature", TestImages.jpeg()).getBody().signature().fileId();

        UUID rival = tenant();
        String rivalDirector = staffToken(rival, "executive_director", null);
        assertThat(overview(rivalDirector).company().signature()).isNull();
        assertThat(overview(rivalDirector).branches()).isEmpty();

        ResponseEntity<String> link = restTemplate.exchange("/api/portal/letterheads/files/" + signature + "/link",
                HttpMethod.GET, entity(rivalDirector, null), String.class);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(link.getBody()).contains("LETTERHEAD_FILE_NOT_FOUND");

        ResponseEntity<String> intoMyBranch = setSignatoryRaw(rivalDirector, branch.toString(), "Mallory", "Director");
        assertThat(intoMyBranch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(intoMyBranch.getBody()).contains("BRANCH_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM letterheads WHERE tenant_id = '" + rival + "'")).isZero();
    }

    @Test
    void anImageOfTheWrongKindOrTypeIsRefused() {
        assertThat(restTemplate.exchange("/api/portal/letterheads/company/images/uploads", HttpMethod.POST,
                entity(director, Map.of("kind", "stamp", "fileName", "a.png", "contentType", "image/png",
                        "sizeBytes", 100)), String.class).getBody()).contains("UNKNOWN_IMAGE_KIND");
        assertThat(restTemplate.exchange("/api/portal/letterheads/company/images/uploads", HttpMethod.POST,
                entity(director, Map.of("kind", "seal", "fileName", "a.pdf", "contentType", "application/pdf",
                        "sizeBytes", 100)), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.exchange("/api/portal/letterheads/company/images/uploads", HttpMethod.POST,
                entity(director, Map.of("kind", "seal", "fileName", "a.png", "contentType", "image/png",
                        "sizeBytes", 3_000_000)), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theAppRoleCannotDeleteALetterhead() {
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'letterheads', 'DELETE')"))
                .isEqualTo("f");
        assertThat(queryString("SELECT relforcerowsecurity FROM pg_class WHERE relname = 'letterheads'"))
                .isEqualTo("t");
    }

    // ------------------------------------------------------------ allocation terms

    @Test
    void theDeveloperWritesTheConditionsAndHomeTrueAddsTheDisclosedFigures() {
        UUID estate = estate(null);
        declareDisclosure(estate);

        EstateLetterTermsDto before = terms(director, estate);
        assertThat(before.version()).isZero();
        assertThat(before.clauses()).isEmpty();
        assertThat(before.missingDisclosure()).isEmpty();
        assertThat(before.generatedClauses()).contains(
                "That an infrastructure fee of ₦3,500,000.00 is payable on allocation.",
                "That the allottee shall begin development within 24 months of allocation.");
        assertThat(before.generatedClauses().getLast()).startsWith("That all payments are made only through HomeTrue.");
        assertThat(before.disclosureVersions().fees()).isEqualTo(1);

        List<String> clauses = List.of(
                "That this land is to be used for the construction of a 4 Bedroom Terrace Duplex.",
                "  That building plans are approved by the estate before work begins.  ");
        EstateLetterTermsDto first = saveTerms(director, estate, clauses).getBody();
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.clauses().get(1)).as("trimmed").isEqualTo("That building plans are approved by the estate before work begins.");
        assertThat(saveTerms(director, estate, clauses).getBody().version()).as("the same again is a no-op").isEqualTo(1);

        EstateLetterTermsDto second = saveTerms(director, estate, List.of(clauses.getFirst())).getBody();
        assertThat(second.version()).isEqualTo(2);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/letter-terms/versions", HttpMethod.GET,
                entity(director, null), String.class).getBody()).contains("\"version\":2", "\"version\":1",
                "Terrace Duplex", "building plans");
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.letter_terms_saved' "
                + "AND target_id = '" + estate + "'")).isEqualTo(2);
    }

    @Test
    void anAmountOrBankDetailsInAClauseIsRefusedAndNothingIsSaved() {
        UUID estate = estate(null);
        ResponseEntity<String> refused = saveTermsRaw(director, estate, List.of(
                "That the land is used for residential purposes only.",
                "That an infrastructure fee of N3,500,000 is paid at allocation.",
                "That balances are paid into Zenith Bank 1012345678."));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("CLAUSE_REFUSED", "clauses[1]", "clauses[2]", "cost disclosure",
                "only through HomeTrue").doesNotContain("clauses[0]");
        assertThat(count("SELECT count(*) FROM estate_letter_terms WHERE estate_id = '" + estate + "'")).isZero();
    }

    @Test
    void anUndeclaredDisclosureIsNamedAndLeftOutOfTheLetter() {
        EstateLetterTermsDto terms = terms(director, estate(null));
        assertThat(terms.missingDisclosure()).containsExactly("fees", "refund_terms", "default_terms");
        assertThat(terms.generatedClauses()).hasSize(1);
        assertThat(terms.disclosureVersions().fees()).isNull();
    }

    @Test
    void branchStaffReadTheirOwnEstatesTermsAndOnlyACompanyWideDirectorSavesThem() {
        UUID doubleKing = branch("Double King", null, null);
        UUID heritage = branch("Heritage", null, null);
        UUID estate = estate(doubleKing);
        saveTerms(director, estate, List.of("That the land is used for residential purposes only."));

        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        assertThat(terms(manager, estate).version()).isEqualTo(1);
        assertThat(saveTermsRaw(manager, estate, List.of("Changed.")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String otherManager = staffToken(tenantId, "branch_manager", heritage);
        ResponseEntity<String> hidden = restTemplate.exchange("/api/portal/estates/" + estate + "/letter-terms",
                HttpMethod.GET, entity(otherManager, null), String.class);
        assertThat(hidden.getStatusCode()).as("another branch's estate").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hidden.getBody()).contains("ESTATE_NOT_FOUND");

        HttpHeaders narrowed = headers(director);
        narrowed.set("X-Branch-Id", doubleKing.toString());
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/letter-terms", HttpMethod.PUT,
                new HttpEntity<>(Map.of("clauses", List.of("Changed.")), narrowed), String.class).getBody())
                .contains("DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE");

        String rivalDirector = staffToken(tenant(), "executive_director", null);
        assertThat(saveTermsRaw(rivalDirector, estate, List.of("Mine now.")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("SELECT count(*) FROM estate_letter_terms WHERE estate_id = '" + estate + "'")).isEqualTo(1);
    }

    @Test
    void theAppRoleCannotRewriteASavedVersion() {
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'estate_letter_terms', 'UPDATE')"))
                .isEqualTo("f");
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'estate_letter_terms', 'DELETE')"))
                .isEqualTo("f");
    }


    // ------------------------------------------------------------ HomeTrue reviews the conditions

    @Test
    void everyVersionWaitsForHomeTrueAndLettersKeepTheLastApprovedOne() {
        UUID estate = estate(null);
        EstateLetterTermsDto first = saveTerms(director, estate, List.of("That the land is used for housing only.")).getBody();
        assertThat(first.reviewStatus()).isEqualTo("pending");
        assertThat(first.approvedVersion()).as("nothing approved: no letters yet").isNull();

        String reviewer = login(ADMIN_EMAIL);
        LetterTermsReviewDto queued = queued(reviewer, estate);
        assertThat(queued.version()).isEqualTo(1);
        assertThat(queued.clauses()).containsExactly("That the land is used for housing only.");
        assertThat(queued.approvedVersion()).isNull();
        assertThat(restTemplate.exchange("/api/admin/letter-terms-reviews", HttpMethod.GET, entity(director, null),
                String.class).getStatusCode()).as("a developer can't review").isEqualTo(HttpStatus.FORBIDDEN);

        // No body at all: the note is optional on approval.
        assertThat(decide(reviewer, queued.termsId(), "approve", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        EstateLetterTermsDto approved = terms(director, estate);
        assertThat(approved.reviewStatus()).isEqualTo("approved");
        assertThat(approved.approvedVersion()).isEqualTo(1);
        assertThat(queue(reviewer)).extracting(LetterTermsReviewDto::estateId).doesNotContain(estate);

        EstateLetterTermsDto second = saveTerms(director, estate, List.of("That the land is used for housing only.",
                "That a charge of five hundred k applies to the gate.")).getBody();
        assertThat(second.reviewStatus()).isEqualTo("pending");
        assertThat(second.approvedVersion()).as("letters keep using version 1 meanwhile").isEqualTo(1);

        LetterTermsReviewDto waiting = queued(reviewer, estate);
        assertThat(waiting.version()).isEqualTo(2);
        assertThat(waiting.approvedClauses()).as("to compare against").containsExactly("That the land is used for housing only.");
        assertThat(decide(reviewer, waiting.termsId(), "reject", Map.of("note", " ")).getStatusCode())
                .as("a rejection needs a note").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(decide(reviewer, waiting.termsId(), "reject",
                Map.of("note", "Clause 2 names a charge. Declare it in the cost disclosure.")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> again = decide(reviewer, waiting.termsId(), "approve", Map.of());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("LETTER_TERMS_ALREADY_DECIDED");

        EstateLetterTermsDto rejected = terms(director, estate);
        assertThat(rejected.reviewStatus()).isEqualTo("rejected");
        assertThat(rejected.reviewNote()).contains("cost disclosure");
        assertThat(rejected.approvedVersion()).isEqualTo(1);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/letter-terms/versions", HttpMethod.GET,
                entity(director, null), String.class).getBody())
                .contains("\"reviewStatus\":\"rejected\"", "\"reviewStatus\":\"approved\"");
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE target_id = '" + estate + "' AND action IN "
                + "('estate.letter_terms_approved', 'estate.letter_terms_rejected')")).isEqualTo(2);
    }

    @Test
    void aVersionReplacedBeforeItWasDecidedCannotBeDecided() {
        UUID estate = estate(null);
        saveTerms(director, estate, List.of("That the land is used for housing only."));
        String reviewer = login(ADMIN_EMAIL);
        UUID firstId = queued(reviewer, estate).termsId();
        saveTerms(director, estate, List.of("That the land is used for housing and offices."));

        ResponseEntity<String> stale = decide(reviewer, firstId, "approve", Map.of());
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("LETTER_TERMS_SUPERSEDED", "version 2");
        assertThat(terms(director, estate).approvedVersion()).isNull();
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/letter-terms/versions", HttpMethod.GET,
                entity(director, null), String.class).getBody())
                .contains("\"reviewStatus\":\"pending\"", "\"reviewStatus\":\"superseded\"");
    }

    @Test
    void aComplianceOfficerReviewsAndADecisionCanNeverBeRewritten() {
        UUID estate = estate(null);
        saveTerms(director, estate, List.of("That the land is used for housing only."));
        String officer = platformToken("compliance_officer");
        assertThat(decide(officer, queued(officer, estate).termsId(), "approve", Map.of("note", "Read in full."))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(terms(director, estate).reviewNote()).isEqualTo("Read in full.");

        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'estate_letter_terms_reviews', '"
                    + privilege + "')")).as(privilege).isEqualTo("f");
        }
    }


    // ------------------------------------------------------------ previewing and approving the letter (DS-5)

    @Test
    void aLetterIsPreviewableAtOnceButApprovableOnlyWithApprovedConditionsAndASignedLetterhead() throws Exception {
        UUID estate = estate(null);
        declareDisclosure(estate);
        LetterApprovalDto empty = letterStatus(director, estate);
        assertThat(empty.approved()).isFalse();
        assertThat(empty.blockers()).containsExactly("no_approved_terms", "signatory_name", "signatory_title", "signature");
        ResponseEntity<String> notReady = approveLetterRaw(director, estate, empty.currentVersions());
        assertThat(notReady.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notReady.getBody()).contains("LETTER_NOT_READY");

        // A preview works from the start, placeholders and all — and no address line, which no issued letter has.
        assertThat(previewText(director, estate, "")).contains("SAMPLE", "[Signatory's name]",
                "[Buyer's phone number]", "That an infrastructure fee of ₦3,500,000.00", "Page 1 of")
                .doesNotContain("address]");

        readyLetterhead();
        saveTerms(director, estate, List.of("That the land is used for the construction of a terrace duplex."));
        assertThat(previewText(director, estate, "?terms=latest")).as("the newest, not yet approved")
                .contains("1. That the land is used for the construction of a terrace duplex.");
        reviewerApproves(estate);

        LetterApprovalDto ready = letterStatus(director, estate);
        assertThat(ready.blockers()).isEmpty();
        assertThat(ready.currentVersions().terms()).isEqualTo(1);
        assertThat(ready.currentVersions().branchLetterhead()).as("a company-level estate").isNull();
        assertThat(ready.currentVersions().templateSource()).as("HomeTrue's wording until the company has its own")
                .isEqualTo("default");
        assertThat(previewText(director, estate, "")).contains("ADAEZE OKAFOR", "Managing Director",
                "1. That the land is used for the construction");
        ResponseEntity<byte[]> jpeg = restTemplate.exchange("/api/portal/estates/" + estate + "/letter-preview/jpeg",
                HttpMethod.GET, entity(director, null), byte[].class);
        assertThat(jpeg.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jpeg.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);

        LetterVersionsDto stale = new LetterVersionsDto(1, ready.currentVersions().companyLetterhead() - 1, null,
                ready.currentVersions().fees(), ready.currentVersions().refundTerms(), ready.currentVersions().defaultTerms(),
                ready.currentVersions().templateSource(), ready.currentVersions().template());
        assertThat(approveLetterRaw(director, estate, stale).getBody()).as("not what was previewed")
                .contains("LETTER_SETUP_CHANGED");

        LetterApprovalDto approved = approveLetter(director, estate, ready.currentVersions());
        assertThat(approved.approved()).isTrue();
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approveLetter(director, estate, ready.currentVersions()).approved()).isTrue();
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.letter_approved' "
                + "AND target_id = '" + estate + "'")).as("approving again is a no-op").isEqualTo(1);
    }

    @Test
    void anyChangeToWhatTheLetterIsDrawnFromWithdrawsTheApproval() throws Exception {
        UUID estate = estate(null);
        declareDisclosure(estate);
        readyLetterhead();
        saveTerms(director, estate, List.of("That the land is used for housing only."));
        reviewerApproves(estate);
        approveLetter(director, estate, letterStatus(director, estate).currentVersions());

        // The cost disclosure: the letter quotes it.
        restTemplate.exchange("/api/portal/estates/" + estate + "/fees", HttpMethod.PUT, entity(director, """
                {"fees":[{"feeType":"infrastructure","amount":4000000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"at_allocation","refundable":true,"isMandatory":true}]}"""), String.class);
        LetterApprovalDto afterFees = letterStatus(director, estate);
        assertThat(afterFees.approved()).isFalse();
        assertThat(afterFees.changedSinceApproval()).containsExactly("fees");

        // The letterhead: a new signatory title.
        approveLetter(director, estate, afterFees.currentVersions());
        setSignatory(director, "company", "Adaeze Okafor", "Chief Executive Officer");
        assertThat(letterStatus(director, estate).changedSinceApproval()).containsExactly("company_letterhead");

        // The conditions: a new version changes nothing until HomeTrue approves it.
        approveLetter(director, estate, letterStatus(director, estate).currentVersions());
        saveTerms(director, estate, List.of("That the land is used for housing and offices."));
        LetterApprovalDto waiting = letterStatus(director, estate);
        assertThat(waiting.approved()).as("letters keep using version 1").isTrue();
        assertThat(waiting.newerTermsAwaitingReview()).isTrue();
        reviewerApproves(estate);
        assertThat(letterStatus(director, estate).changedSinceApproval()).containsExactly("terms");

        // Withdrawn by hand.
        approveLetter(director, estate, letterStatus(director, estate).currentVersions());
        LetterApprovalDto withdrawn = restTemplate.exchange("/api/portal/estates/" + estate
                + "/letter-approval/withdraw", HttpMethod.POST, entity(director, null), LetterApprovalDto.class).getBody();
        assertThat(withdrawn.approved()).isFalse();
        assertThat(withdrawn.approvedAt()).isNull();
        assertThat(withdrawn.changedSinceApproval()).isEmpty();
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.letter_approval_withdrawn' "
                + "AND target_id = '" + estate + "'")).isEqualTo(1);
    }

    @Test
    void branchStaffPreviewTheirEstatesLetterAndOnlyACompanyWideDirectorApprovesIt() throws Exception {
        UUID branch = branch("Double King", "12 Airport Road", "+2348011111111");
        UUID estate = estate(branch);
        readyLetterhead();
        saveTerms(director, estate, List.of("That the land is used for housing only."));
        reviewerApproves(estate);

        String manager = staffToken(tenantId, "branch_manager", branch);
        LetterApprovalDto seen = letterStatus(manager, estate);
        assertThat(seen.currentVersions().branchLetterhead()).as("the branch sets nothing of its own").isZero();
        assertThat(previewText(manager, estate, "")).contains("12 Airport Road", "ADAEZE OKAFOR");
        assertThat(approveLetterRaw(manager, estate, seen.currentVersions()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders narrowed = headers(director);
        narrowed.set("X-Branch-Id", branch.toString());
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/letter-approval", HttpMethod.POST,
                new HttpEntity<>(Map.of("versions", seen.currentVersions()), narrowed), String.class).getBody())
                .contains("DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE");

        String rival = staffToken(tenant(), "executive_director", null);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/letter-preview/pdf", HttpMethod.GET,
                entity(rival, null), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        approveLetter(director, estate, seen.currentVersions());
        setSignatory(director, branch.toString(), "Bola Ade", "Branch Manager");
        assertThat(letterStatus(director, estate).changedSinceApproval()).containsExactly("branch_letterhead");
        assertThat(letterStatus(director, estate).blockers()).as("the branch signer has no signature yet")
                .containsExactly("signature");

        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'estate_letter_approvals', '"
                    + privilege + "')")).as(privilege).isEqualTo("f");
        }
    }


    // ------------------------------------------------------------ letter templates

    @Test
    void aCompanyWritesItsOwnWordingAndLettersUseItOnceHomeTrueApprovesIt() throws Exception {
        LetterTemplatesOverviewDto empty = templates(director);
        assertThat(empty.company().current()).isNull();
        assertThat(empty.company().inUse()).isEqualTo("default");
        assertThat(empty.fields()).contains("plot", "estate", "applicationDate");

        ResponseEntity<String> refused = saveTemplate(director, "company", Map.of(
                "opening", "We allocate to you {plot}.",
                "closing", "Your balance of {amount} is due.",
                "signOff", "Pay 2 million to finish.",
                "standardClauses", List.of("That the company is notified before work begins.",
                        "That balances are paid into Zenith Bank 1012345678.")));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("TEMPLATE_REFUSED", "\"opening\"", "{plot} and {estate}",
                "\"closing\"", "{amount} isn't a fill-in field", "\"signOff\"", "standardClauses[1]")
                .doesNotContain("standardClauses[0]");
        assertThat(count("SELECT count(*) FROM letter_templates WHERE tenant_id = '" + tenantId + "'")).isZero();

        UUID estate = estate(null);
        readyLetterhead();
        saveTerms(director, estate, List.of("That the land is used for housing only."));
        reviewerApproves(estate);
        approveLetter(director, estate, letterStatus(director, estate).currentVersions());

        Map<String, Object> wording = Map.of(
                "opening", "With reference to your application dated {applicationDate} for a plot of land at {estate}, "
                        + "we are glad to convey to you the allocation of {plot}, subject to the following terms.",
                "closing", "Our site office is open Monday to Saturday for any questions.",
                "signOff", "Congratulations!",
                "standardClauses", List.of("That the company is notified before any work begins on site."));
        assertThat(saveTemplate(director, "company", wording).getStatusCode()).isEqualTo(HttpStatus.OK);
        LetterTemplateDto saved = templates(director).company();
        assertThat(saved.current().reviewStatus()).isEqualTo("pending");
        assertThat(saved.inUse()).as("not approved yet").isEqualTo("default");
        assertThat(saveTemplate(director, "company", wording).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(templates(director).company().current().version()).as("the same again is a no-op").isEqualTo(1);
        assertThat(letterStatus(director, estate).approved()).as("a pending template changes nothing").isTrue();

        assertThat(previewText(director, estate, "?terms=latest")).contains(
                "With reference to your application dated [date of application] for a plot of land at Terms Estate",
                "1. That the company is notified before any work begins", "2. That the land is used for housing only.",
                "Our site office is open", "Congratulations!", "pproved by HomeTrue");

        String reviewer = login(ADMIN_EMAIL);
        LetterTemplateReviewDto queued = templateQueue(reviewer).stream()
                .filter(t -> t.tenantId().equals(tenantId)).findFirst().orElseThrow();
        assertThat(queued.source()).isEqualTo("company");
        assertThat(decideTemplate(reviewer, queued.templateId(), "approve").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(templates(director).company().inUse()).isEqualTo("company");
        LetterApprovalDto lapsed = letterStatus(director, estate);
        assertThat(lapsed.approved()).isFalse();
        assertThat(lapsed.changedSinceApproval()).containsExactly("template");
        assertThat(lapsed.currentVersions().templateSource()).isEqualTo("company");
        assertThat(previewText(director, estate, "")).contains("With reference to your application dated",
                "Preview only");
    }

    @Test
    void aBranchsApprovedTemplateReplacesTheCompanysForItsEstatesOnly() {
        UUID doubleKing = branch("Double King", null, null);
        UUID heritage = branch("Heritage", null, null);
        String reviewer = login(ADMIN_EMAIL);
        saveTemplate(director, "company", template("We allocate {plot} at {estate} to you."));
        approveNewestTemplate(reviewer, null);
        saveTemplate(director, doubleKing.toString(), template("Double King allocates {plot} at {estate} to you."));
        approveNewestTemplate(reviewer, doubleKing);

        LetterTemplatesOverviewDto overview = templates(director);
        assertThat(overview.branches()).filteredOn(b -> b.branchId().equals(doubleKing))
                .extracting(LetterTemplateDto::inUse).containsExactly("branch");
        assertThat(overview.branches()).filteredOn(b -> b.branchId().equals(heritage))
                .extracting(LetterTemplateDto::inUse).containsExactly("company");
        assertThat(letterStatus(director, estate(doubleKing)).currentVersions().templateSource()).isEqualTo("branch");
        assertThat(letterStatus(director, estate(heritage)).currentVersions().templateSource()).isEqualTo("company");

        String manager = staffToken(tenantId, "branch_manager", doubleKing);
        assertThat(saveTemplate(manager, doubleKing.toString(), template("Mine {plot} {estate}.")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        saveTemplate(director, doubleKing.toString(), template("Version two: {plot} at {estate}."));
        UUID second = newestTemplate(reviewer, doubleKing).templateId();
        saveTemplate(director, doubleKing.toString(), template("Version three: {plot} at {estate}."));
        ResponseEntity<String> stale = decideTemplate(reviewer, second, "approve");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("LETTER_TEMPLATE_SUPERSEDED");

        for (String table : List.of("letter_templates", "letter_template_reviews")) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', '" + table + "', '"
                        + privilege + "')")).as(table + " " + privilege).isEqualTo("f");
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private LetterTemplatesOverviewDto templates(String token) {
        ResponseEntity<LetterTemplatesOverviewDto> response = restTemplate.exchange("/api/portal/letter-templates",
                HttpMethod.GET, entity(token, null), LetterTemplatesOverviewDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> saveTemplate(String token, String target, Map<String, Object> body) {
        return restTemplate.exchange("/api/portal/letter-templates/" + target, HttpMethod.PUT, entity(token, body),
                String.class);
    }

    private static Map<String, Object> template(String opening) {
        return Map.of("opening", opening, "signOff", "Congratulations.", "standardClauses", List.of());
    }

    private List<LetterTemplateReviewDto> templateQueue(String token) {
        ResponseEntity<LetterTemplateReviewDto[]> response = restTemplate.exchange(
                "/api/admin/letter-template-reviews", HttpMethod.GET, entity(token, null), LetterTemplateReviewDto[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private LetterTemplateReviewDto newestTemplate(String reviewer, UUID branch) {
        return templateQueue(reviewer).stream()
                .filter(t -> t.tenantId().equals(tenantId) && Objects.equals(t.branchId(), branch))
                .findFirst().orElseThrow();
    }

    private void approveNewestTemplate(String reviewer, UUID branch) {
        assertThat(decideTemplate(reviewer, newestTemplate(reviewer, branch).templateId(), "approve").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> decideTemplate(String token, UUID templateId, String decision) {
        return restTemplate.exchange("/api/admin/letter-template-reviews/" + templateId + "/" + decision,
                HttpMethod.POST, entity(token, Map.of()), String.class);
    }

    private void readyLetterhead() throws IOException, InterruptedException {
        setSignatory(director, "company", "Adaeze Okafor", "Managing Director");
        uploadImage(director, "company", "signature", TestImages.jpeg());
    }

    private void reviewerApproves(UUID estate) {
        String reviewer = login(ADMIN_EMAIL);
        assertThat(decide(reviewer, queued(reviewer, estate).termsId(), "approve", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private LetterApprovalDto letterStatus(String token, UUID estate) {
        ResponseEntity<LetterApprovalDto> response = restTemplate.exchange("/api/portal/estates/" + estate
                + "/letter-approval", HttpMethod.GET, entity(token, null), LetterApprovalDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private LetterApprovalDto approveLetter(String token, UUID estate, LetterVersionsDto versions) {
        ResponseEntity<LetterApprovalDto> response = restTemplate.exchange("/api/portal/estates/" + estate
                + "/letter-approval", HttpMethod.POST, entity(token, Map.of("versions", versions)), LetterApprovalDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> approveLetterRaw(String token, UUID estate, LetterVersionsDto versions) {
        return restTemplate.exchange("/api/portal/estates/" + estate + "/letter-approval", HttpMethod.POST,
                entity(token, Map.of("versions", versions)), String.class);
    }

    private String previewText(String token, UUID estate, String query) throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange("/api/portal/estates/" + estate
                + "/letter-preview/pdf" + query, HttpMethod.GET, entity(token, null), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        // An attachment: shown inline, Swagger UI reads the bytes as text and its download saves a damaged copy.
        assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
        try (PDDocument document = Loader.loadPDF(response.getBody())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private List<LetterTermsReviewDto> queue(String token) {
        ResponseEntity<LetterTermsReviewDto[]> response = restTemplate.exchange("/api/admin/letter-terms-reviews",
                HttpMethod.GET, entity(token, null), LetterTermsReviewDto[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private LetterTermsReviewDto queued(String token, UUID estate) {
        return queue(token).stream().filter(r -> r.estateId().equals(estate)).findFirst().orElseThrow();
    }

    private ResponseEntity<String> decide(String token, UUID termsId, String decision, Object body) {
        return restTemplate.exchange("/api/admin/letter-terms-reviews/" + termsId + "/" + decision, HttpMethod.POST,
                entity(token, body), String.class);
    }

    /** A platform staff member holding only {@code roleCode}. */
    private String platformToken(String roleCode) {
        String email = register();
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "'");
        return login(email);
    }

    private EstateLetterTermsDto terms(String token, UUID estate) {
        ResponseEntity<EstateLetterTermsDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estate + "/letter-terms", HttpMethod.GET, entity(token, null),
                EstateLetterTermsDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<EstateLetterTermsDto> saveTerms(String token, UUID estate, List<String> clauses) {
        ResponseEntity<EstateLetterTermsDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estate + "/letter-terms", HttpMethod.PUT,
                entity(token, Map.of("clauses", clauses)), EstateLetterTermsDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private ResponseEntity<String> saveTermsRaw(String token, UUID estate, List<String> clauses) {
        return restTemplate.exchange("/api/portal/estates/" + estate + "/letter-terms", HttpMethod.PUT,
                entity(token, Map.of("clauses", clauses)), String.class);
    }

    /** No boundary: the terms don't need one. A branch's estate when {@code branch} is set. */
    private UUID estate(UUID branch) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Terms Estate " + UUID.randomUUID().toString()
                .substring(0, 6), "state", "FCT", "area", "Lugbe", "city", "Abuja"));
        if (branch != null) {
            body.put("branchId", branch.toString());
        }
        ResponseEntity<Map> created = restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(director, body), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private void declareDisclosure(UUID estate) {
        for (String[] part : List.of(
                new String[]{"fees", """
                        {"fees":[{"feeType":"application","amount":10000,"currency":"NGN","isFixed":true,
                          "dueTrigger":"at_application","refundable":false,"isMandatory":true},
                         {"feeType":"infrastructure","amount":3500000,"currency":"NGN","isFixed":true,
                          "dueTrigger":"at_allocation","refundable":true,"isMandatory":true}]}"""},
                new String[]{"refund-terms", """
                        {"deductionPct":20.00,"processingDays":90,"appliesTo":"total_price",
                         "nonRefundableFeeTypes":["application"]}"""},
                new String[]{"default-terms", """
                        {"revocationTrigger":"Failure to begin development within 24 months.",
                         "revocationNoticeDays":30,
                         "onRevocationRefund":"Payments to date are refunded less a 20% administrative charge.",
                         "developmentDeadlineMonths":24, "transferRequiresConsent":true, "penaltyTiers":[]}"""})) {
            ResponseEntity<String> declared = restTemplate.exchange("/api/portal/estates/" + estate + "/" + part[0],
                    HttpMethod.PUT, entity(director, part[1]), String.class);
            assertThat(declared.getStatusCode().is2xxSuccessful()).as(part[0] + ": " + declared.getBody()).isTrue();
        }
    }

    private LetterheadOverviewDto overview(String token) {
        ResponseEntity<LetterheadOverviewDto> response = restTemplate.exchange("/api/portal/letterheads",
                HttpMethod.GET, entity(token, null), LetterheadOverviewDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private LetterheadDto branchLetterhead(String token, UUID branch) {
        return overview(token).branches().stream().filter(b -> branch.equals(b.branchId())).findFirst().orElseThrow();
    }

    private ResponseEntity<LetterheadDto> setSignatory(String token, String target, String name, String title) {
        return restTemplate.exchange("/api/portal/letterheads/" + target + "/signatory", HttpMethod.PUT,
                entity(token, Map.of("name", name, "title", title)), LetterheadDto.class);
    }

    private ResponseEntity<String> setSignatoryRaw(String token, String target, String name, String title) {
        return restTemplate.exchange("/api/portal/letterheads/" + target + "/signatory", HttpMethod.PUT,
                entity(token, Map.of("name", name, "title", title)), String.class);
    }

    private ResponseEntity<LetterheadDto> uploadImage(String token, String target, String kind, byte[] bytes)
            throws IOException, InterruptedException {
        ResponseEntity<UploadTicket> ticket = restTemplate.exchange(
                "/api/portal/letterheads/" + target + "/images/uploads", HttpMethod.POST,
                entity(token, Map.of("kind", kind, "fileName", kind + ".jpg", "contentType", "image/jpeg",
                        "sizeBytes", bytes.length)), UploadTicket.class);
        assertThat(ticket.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(put(ticket.getBody(), bytes)).isEqualTo(200);
        ResponseEntity<LetterheadDto> confirmed = restTemplate.exchange("/api/portal/letterheads/" + target
                + "/images/" + ticket.getBody().fileId() + "/confirm", HttpMethod.POST, entity(token, null),
                LetterheadDto.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return confirmed;
    }

    private LetterheadDto exchange(String token, HttpMethod method, String url, Object body) {
        ResponseEntity<LetterheadDto> response = restTemplate.exchange(url, method, entity(token, body), LetterheadDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private UUID branch(String name, String street, String phone) {
        ResponseEntity<PortalBranchDto> response = restTemplate.exchange("/api/portal/branches", HttpMethod.POST,
                entity(director, new CreateBranchRequest(name, street, street == null ? null : "Abuja",
                        street == null ? null : "FCT", phone, null)), PortalBranchDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private int put(UploadTicket ticket, byte[] bytes) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        ticket.headers().forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpResponse<byte[]> get(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private UUID tenant() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Letterhead Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(login(ADMIN_EMAIL), request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String staffToken(UUID owningTenantId, String roleCode, UUID branchId) {
        String email = register();
        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, "
                + (branchId == null ? "NULL" : "'" + branchId + "'") + " FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "'");
        return login(email);
    }

    private String buyerToken() {
        return login(register());
    }

    private String register() {
        String email = "person+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Person", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpEntity<Object> entity(String token, Object body) {
        return new HttpEntity<>(body, headers(token));
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
