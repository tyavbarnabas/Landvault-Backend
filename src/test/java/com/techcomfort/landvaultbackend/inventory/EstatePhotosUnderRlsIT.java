package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstatePhotoDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceListingDto;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.testsupport.LetterApprovalFixture;
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

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estate photos end to end: real uploads to RustFS (the docker-compose image),
 * metadata removed at confirmation, order and cover, the publication
 * condition, and the marketplace gallery. Wired to the restricted app role so
 * {@code estate_photos}' row security is what the portal actually runs under.
 * <strong>Do not convert this class to {@code @ServiceConnection}</strong>: the
 * company-isolation tests would then pass whether the policy exists or not.
 * See AGENTS.md, "Estate photos".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class EstatePhotosUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_photos_it";
    private static final String APP_ROLE_PASSWORD = "photos-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";
    private static final String ACCESS_KEY = "photos-access-key";
    private static final String SECRET_KEY = "photos-secret-key-123";
    private static final String PUBLIC_BUCKET = "photos-public";
    private static final String PRIVATE_BUCKET = "photos-private";
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
        registry.add("landvault.storage.endpoint", EstatePhotosUnderRlsIT::endpoint);
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
        admin.putBucketPolicy(r -> r.bucket(PUBLIC_BUCKET).policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                 "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}""".formatted(PUBLIC_BUCKET)));
    }

    @AfterAll
    static void closeAdmin() {
        admin.close();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID tenantId;
    private String token;
    private double x;
    private double y;

    @BeforeEach
    void setUp() {
        int n = PATCH.getAndIncrement();
        x = 7.250 + 0.030 * (n % 8);
        y = 8.850 + 0.030 * (n / 8);
        tenantId = tenant();
        token = staffToken(tenantId, "executive_director");
    }

    // ------------------------------------------------------------ the flow

    @Test
    void anUploadedPhotoLosesItsMetadataKeepsItsOrientationAndBecomesTheCover() throws Exception {
        UUID estate = estate("Gallery Gardens");
        byte[] original = TestImages.jpegWithMetadata(6);

        UploadTicket ticket = requestUpload(token, estate, "IMG_2041 at my house.jpg", "image/jpeg", original.length);
        assertThat(put(ticket, original)).isEqualTo(200);
        ResponseEntity<EstatePhotoDto> confirmed = confirm(token, estate, ticket.fileId(), "{\"caption\":\"  The gate  \"}");

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        EstatePhotoDto photo = confirmed.getBody();
        assertThat(photo.cover()).isTrue();
        assertThat(photo.position()).isZero();
        assertThat(photo.caption()).isEqualTo("The gate");
        assertThat(photo.fileType()).isEqualTo("jpeg");
        assertThat(photo.sizeBytes()).as("the metadata is gone").isLessThan(original.length);

        byte[] served = get(photo.url()).body();
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain("SECRET").contains("Exif");
        assertThat(ImageIO.read(new ByteArrayInputStream(served))).as("still a real image").isNotNull();
        assertThat(served.length).isEqualTo((int) photo.sizeBytes());

        String originalUrl = endpoint() + "/" + PUBLIC_BUCKET + "/" + URI.create(ticket.uploadUrl()).getPath()
                .substring(("/" + PUBLIC_BUCKET + "/").length());
        assertThat(get(originalUrl).statusCode()).as("the original, metadata and all, is deleted").isEqualTo(404);
        assertThat(photo.url()).as("cleaned under a new address, never the original's").isNotEqualTo(originalUrl);

        ResponseEntity<EstatePhotoDto> again = confirm(token, estate, ticket.fileId(), null);
        assertThat(again.getStatusCode()).as("confirming twice").isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().id()).isEqualTo(photo.id());
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.photo_added' AND target_id = '"
                + estate + "'")).isEqualTo(1);
    }

    @Test
    void aFileThatIsNotReallyAnImageItCanReadIsRefusedAndDeleted() throws Exception {
        UUID estate = estate("Broken Gardens");
        byte[] truncated = Arrays.copyOf(TestImages.jpegWithMetadata(1), 300);

        UploadTicket ticket = requestUpload(token, estate, "half.jpg", "image/jpeg", truncated.length);
        assertThat(put(ticket, truncated)).isEqualTo(200);
        ResponseEntity<String> refused = restTemplate.exchange(path(estate, "/" + ticket.fileId() + "/confirm"),
                HttpMethod.POST, entity(token, null), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("IMAGE_UNREADABLE");
        assertThat(queryString("SELECT status FROM stored_files WHERE id = '" + ticket.fileId() + "'"))
                .as("recorded as rejected, not rolled back to pending").isEqualTo("REJECTED");
        assertThat(list(token, estate)).isEmpty();
    }

    @Test
    void orderCoverAndDeleteKeepPositionsContiguousAndOneCover() throws Exception {
        UUID estate = estate("Ordered Gardens");
        EstatePhotoDto a = addPhoto(token, estate);
        EstatePhotoDto b = addPhoto(token, estate);
        EstatePhotoDto c = addPhoto(token, estate);
        assertThat(List.of(a.cover(), b.cover(), c.cover())).containsExactly(true, false, false);

        List<EstatePhotoDto> reordered = exchangeList(HttpMethod.PUT, path(estate, "/order"),
                "{\"photoIds\":[\"" + c.id() + "\",\"" + a.id() + "\",\"" + b.id() + "\"]}");
        assertThat(reordered).extracting(EstatePhotoDto::id).containsExactly(c.id(), a.id(), b.id());
        assertThat(reordered).extracting(EstatePhotoDto::position).containsExactly(0, 1, 2);
        assertThat(reordered.get(1).cover()).as("reordering doesn't move the cover").isTrue();

        List<EstatePhotoDto> covered = exchangeList(HttpMethod.PUT, path(estate, "/cover"), "{\"photoId\":\"" + b.id() + "\"}");
        assertThat(covered).filteredOn(EstatePhotoDto::cover).extracting(EstatePhotoDto::id).containsExactly(b.id());

        assertThat(restTemplate.exchange(path(estate, "/" + b.id()), HttpMethod.DELETE, entity(token, null), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<EstatePhotoDto> after = list(token, estate);
        assertThat(after).extracting(EstatePhotoDto::id).containsExactly(c.id(), a.id());
        assertThat(after).extracting(EstatePhotoDto::position).containsExactly(0, 1);
        assertThat(after).filteredOn(EstatePhotoDto::cover).extracting(EstatePhotoDto::id)
                .as("the next in order takes over the cover").containsExactly(c.id());
        assertThat(get(b.url()).statusCode()).as("its file is deleted from storage").isEqualTo(404);
        assertThat(count("SELECT count(*) FROM audit_log_entries WHERE target_id = '" + estate
                + "' AND action IN ('estate.photo_cover_changed', 'estate.photo_deleted')")).isEqualTo(2);
    }

    @Test
    void aReorderThatLeavesOutOrRepeatsAPhotoChangesNothing() throws Exception {
        UUID estate = estate("Stale Gardens");
        EstatePhotoDto a = addPhoto(token, estate);
        EstatePhotoDto b = addPhoto(token, estate);

        for (String ids : List.of("\"" + a.id() + "\"", "\"" + a.id() + "\",\"" + a.id() + "\"",
                "\"" + a.id() + "\",\"" + b.id() + "\",\"" + UUID.randomUUID() + "\"")) {
            ResponseEntity<String> refused = restTemplate.exchange(path(estate, "/order"), HttpMethod.PUT,
                    entity(token, "{\"photoIds\":[" + ids + "]}"), String.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody()).contains("PHOTO_ORDER_MISMATCH");
        }
        assertThat(list(token, estate)).extracting(EstatePhotoDto::id).containsExactly(a.id(), b.id());
    }

    @Test
    void anEstateTakesAtMostTwentyPhotos() {
        UUID estate = estate("Full Gardens");
        for (int i = 0; i < 20; i++) {
            insertPhotoRow(estate, i, i == 0);
        }
        ResponseEntity<String> refused = restTemplate.exchange(path(estate, "/uploads"), HttpMethod.POST,
                entity(token, uploadBody("one-more.jpg", "image/jpeg", 1000)), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("ESTATE_PHOTO_LIMIT_REACHED");
    }

    @Test
    void theTypeAndSizeLimitsAreTheStoredOnes() {
        UUID estate = estate("Limits Gardens");
        ResponseEntity<String> pdf = restTemplate.exchange(path(estate, "/uploads"), HttpMethod.POST,
                entity(token, uploadBody("brochure.pdf", "application/pdf", 1000)), String.class);
        ResponseEntity<String> big = restTemplate.exchange(path(estate, "/uploads"), HttpMethod.POST,
                entity(token, uploadBody("huge.jpg", "image/jpeg", 5_242_881)), String.class);
        ResponseEntity<String> exactly = restTemplate.exchange(path(estate, "/uploads"), HttpMethod.POST,
                entity(token, uploadBody("ok.jpg", "image/jpeg", 5_242_880)), String.class);

        assertThat(pdf.getBody()).contains("FILE_TYPE_NOT_ALLOWED");
        assertThat(big.getBody()).contains("FILE_TOO_LARGE");
        assertThat(exactly.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------ who may

    @Test
    void anotherCompanyCanNeitherSeeNorTouchTheseEstatesPhotos() throws Exception {
        UUID estate = estate("Private Gardens");
        EstatePhotoDto photo = addPhoto(token, estate);
        String rival = staffToken(tenant(), "executive_director");

        assertThat(restTemplate.exchange(path(estate, ""), HttpMethod.GET, entity(rival, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange(path(estate, "/uploads"), HttpMethod.POST,
                entity(rival, uploadBody("x.jpg", "image/jpeg", 100)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange(path(estate, "/" + photo.id()), HttpMethod.DELETE, entity(rival, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(list(token, estate)).hasSize(1);
    }

    @Test
    void onlyTheUploaderCanConfirmTheirUpload() throws Exception {
        UUID estate = estate("Colleague Gardens");
        String colleague = staffToken(tenantId, "executive_director");
        byte[] image = TestImages.jpeg();
        UploadTicket ticket = requestUpload(token, estate, "mine.jpg", "image/jpeg", image.length);
        assertThat(put(ticket, image)).isEqualTo(200);

        ResponseEntity<String> refused = restTemplate.exchange(path(estate, "/" + ticket.fileId() + "/confirm"),
                HttpMethod.POST, entity(colleague, null), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody()).contains("FILE_NOT_FOUND");
    }

    @Test
    void aSalesManagerCanSeeThePhotosButNotChangeThem() throws Exception {
        UUID estate = estate("Viewed Gardens");
        addPhoto(token, estate);
        String sales = staffToken(tenantId, "sales_manager");

        assertThat(list(sales, estate)).hasSize(1);
        assertThat(restTemplate.exchange(path(estate, "/uploads"), HttpMethod.POST,
                entity(sales, uploadBody("x.jpg", "image/jpeg", 100)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------- marketplace and publishing

    @Test
    void anEstateWithNoPhotoCannotBePublishedAndTheGalleryAppearsOnceOneIsAdded() throws Exception {
        UUID estate = listableEstate("Photo Gate Gardens");

        ResponseEntity<String> refused = publish(estate);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("PUBLICATION_NO_PHOTO");
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate, HttpMethod.GET, entity(token, null), String.class)
                .getBody()).contains("\"hasPhoto\":false");

        EstatePhotoDto first = addPhoto(token, estate);
        EstatePhotoDto second = addPhoto(token, estate);
        exchangeList(HttpMethod.PUT, path(estate, "/cover"), "{\"photoId\":\"" + second.id() + "\"}");
        assertThat(publish(estate).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> raw = restTemplate.getForEntity("/api/marketplace/estates/" + estate, String.class);
        MarketplaceListingDto listing = restTemplate.getForObject("/api/marketplace/estates/" + estate, MarketplaceListingDto.class);
        assertThat(listing.imageUrl()).as("the cover is the listing's image").isEqualTo(second.url());
        assertThat(listing.photos()).extracting(p -> p.url()).as("cover first").containsExactly(second.url(), first.url());
        assertThat(raw.getBody()).as("never the file's own name or who uploaded it")
                .doesNotContain("photo.jpg").doesNotContain(first.uploadedBy().toString());
        assertThat(feed()).extracting(MarketplaceListingDto::imageUrl).contains(second.url());

        restTemplate.exchange(path(estate, "/" + first.id()), HttpMethod.DELETE, entity(token, null), Void.class);
        restTemplate.exchange(path(estate, "/" + second.id()), HttpMethod.DELETE, entity(token, null), Void.class);
        assertThat(feed()).extracting(MarketplaceListingDto::id).as("no photo, no listing").doesNotContain(estate);
        assertThat(queryString("SELECT published FROM estates WHERE id = '" + estate + "'"))
                .as("the developer's intent is untouched").isEqualTo("t");
        addPhoto(token, estate);
        assertThat(feed()).extracting(MarketplaceListingDto::id).as("back without republishing").contains(estate);
    }

    /** Decided with the user: estates live when the rule arrived keep their listing. */
    @Test
    void anExemptEstateStaysListedWithNoImage() {
        UUID estate = listableEstate("Exempt Gardens");
        execute("UPDATE estates SET photo_requirement_exempt = true WHERE id = '" + estate + "'");
        assertThat(publish(estate).getStatusCode()).isEqualTo(HttpStatus.OK);

        MarketplaceListingDto listing = restTemplate.getForObject("/api/marketplace/estates/" + estate, MarketplaceListingDto.class);
        assertThat(listing.imageUrl()).isNull();
        assertThat(listing.photos()).isEmpty();
    }

    @Test
    void theAppRoleCannotHardDeleteAPhoto() throws Exception {
        UUID estate = estate("Kept Gardens");
        addPhoto(token, estate);
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'estate_photos', 'DELETE')")).isEqualTo("f");
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'marketplace_estate_photos', 'INSERT')"))
                .isEqualTo("f");
    }

    // ============================================================ helpers

    private EstatePhotoDto addPhoto(String asToken, UUID estate) throws Exception {
        byte[] image = TestImages.jpegWithMetadata(1);
        UploadTicket ticket = requestUpload(asToken, estate, "photo.jpg", "image/jpeg", image.length);
        assertThat(put(ticket, image)).isEqualTo(200);
        ResponseEntity<EstatePhotoDto> confirmed = confirm(asToken, estate, ticket.fileId(), null);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return confirmed.getBody();
    }

    private UploadTicket requestUpload(String asToken, UUID estate, String name, String type, long size) {
        ResponseEntity<UploadTicket> ticket = restTemplate.exchange(path(estate, "/uploads"), HttpMethod.POST,
                entity(asToken, uploadBody(name, type, size)), UploadTicket.class);
        assertThat(ticket.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ticket.getBody();
    }

    private static String uploadBody(String name, String type, long size) {
        return "{\"fileName\":\"" + name + "\",\"contentType\":\"" + type + "\",\"sizeBytes\":" + size + "}";
    }

    private ResponseEntity<EstatePhotoDto> confirm(String asToken, UUID estate, UUID fileId, String body) {
        return restTemplate.exchange(path(estate, "/" + fileId + "/confirm"), HttpMethod.POST,
                entity(asToken, body), EstatePhotoDto.class);
    }

    private List<EstatePhotoDto> list(String asToken, UUID estate) {
        ResponseEntity<List<EstatePhotoDto>> response = restTemplate.exchange(path(estate, ""), HttpMethod.GET,
                entity(asToken, null), new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<EstatePhotoDto> exchangeList(HttpMethod method, String url, String body) {
        ResponseEntity<List<EstatePhotoDto>> response = restTemplate.exchange(url, method, entity(token, body),
                new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<MarketplaceListingDto> feed() {
        return restTemplate.exchange("/api/marketplace/estates?limit=100", HttpMethod.GET, null,
                new ParameterizedTypeReference<PageResponse<MarketplaceListingDto>>() {
                }).getBody().items();
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

    private static String path(UUID estate, String rest) {
        return "/api/portal/estates/" + estate + "/photos" + rest;
    }

    private UUID estate(String name) {
        String body = "{\"name\":\"" + name + " " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"state\":\"FCT\",\"footprint\":" + rect(x, y, 0.010, 0.010) + "}";
        ResponseEntity<String> created = restTemplate.exchange("/api/portal/estates", HttpMethod.POST, entity(token, body), String.class);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        return idOf(created);
    }

    /** Meets every condition but the photo: verified and entitled company, boundary, a plot, fees and refund terms. */
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
        execute(LetterApprovalFixture.approvedLetterSql(estate)); // changeset 095: an approved letter
        return estate;
    }

    private ResponseEntity<String> publish(UUID estate) {
        return restTemplate.exchange("/api/portal/estates/" + estate + "/publish", HttpMethod.POST, entity(token, ""), String.class);
    }

    /** A photo row pointing at a ready file, in SQL — for the count limit, where the bytes don't matter. */
    private void insertPhotoRow(UUID estate, int position, boolean cover) {
        execute("""
                WITH f AS (
                    INSERT INTO stored_files (id, tenant_id, created_at, deleted, visibility, bucket, object_key,
                                              purpose, file_name, file_type, size_bytes, status, uploaded_by,
                                              upload_link_expires_at, confirmed_at)
                    SELECT gen_random_uuid(), '%1$s', now(), false, 'PUBLIC', '%2$s',
                           'estate-photo/' || gen_random_uuid() || '.jpg', 'estate-photo', 'x.jpg', 'JPEG', 1,
                           'READY', (SELECT id FROM users ORDER BY created_at LIMIT 1), now(), now()
                    RETURNING id
                )
                INSERT INTO estate_photos (id, tenant_id, created_at, deleted, estate_id, file_id, position, cover)
                SELECT gen_random_uuid(), '%1$s', now(), false, '%3$s', f.id, %4$d, %5$s FROM f
                """.formatted(tenantId, PUBLIC_BUCKET, estate, position, cover));
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
                new CompanyIdentityDto("Photo Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
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

    private String staffToken(UUID owningTenantId, String roleCode) {
        String email = "staff+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, NULL FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "'");
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
