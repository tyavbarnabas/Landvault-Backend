package com.techcomfort.landvaultbackend.storage;

import com.techcomfort.landvaultbackend.storage.internal.service.AbandonedUploadSweeper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage module against a real S3-compatible store (RustFS, the same image
 * as docker-compose): links that really upload, the checks at confirmation, the
 * two buckets, and cleanup. See AGENTS.md, "File storage".
 */
@SpringBootTest
@Testcontainers
class StorageIT {

    private static final String ACCESS_KEY = "it-access-key";
    private static final String SECRET_KEY = "it-secret-key-123";
    private static final String PUBLIC_BUCKET = "it-public";
    private static final String PRIVATE_BUCKET = "it-private";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.1"))
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withExposedPorts(9000)
            .waitingFor(Wait.forListeningPort());

    private static S3Client admin;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("landvault.storage.endpoint", StorageIT::endpoint);
        registry.add("landvault.storage.access-key", () -> ACCESS_KEY);
        registry.add("landvault.storage.secret-key", () -> SECRET_KEY);
        registry.add("landvault.storage.public-bucket", () -> PUBLIC_BUCKET);
        registry.add("landvault.storage.private-bucket", () -> PRIVATE_BUCKET);
        registry.add("landvault.storage.public-base-url", () -> endpoint() + "/" + PUBLIC_BUCKET);
        registry.add("landvault.storage.max-upload-size", () -> "1MB");
    }

    private static String endpoint() {
        return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
    }

    /** What docker-compose's storage-init does locally: two buckets, only the public one readable by anyone. */
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

    private static final byte[] JPEG = jpeg(2048);
    private static final Set<FileType> IMAGES = EnumSet.of(FileType.JPEG, FileType.PNG, FileType.WEBP);
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    StorageApi storage;
    @Autowired
    AbandonedUploadSweeper sweeper;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aRealImageUploadedWithTheLinkIsConfirmedAndServedPublicly() throws Exception {
        UUID owner = user();
        UploadTicket ticket = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));

        assertThat(put(ticket, JPEG)).isEqualTo(200);
        StoredFileInfo file = storage.confirmUpload(ticket.fileId(), owner);

        assertThat(file.ready()).isTrue();
        assertThat(file.fileType()).isEqualTo(FileType.JPEG);
        assertThat(file.sizeBytes()).isEqualTo(JPEG.length);
        assertThat(file.publicUrl()).startsWith(endpoint() + "/" + PUBLIC_BUCKET + "/estate-photo/").endsWith(".jpg");
        HttpResponse<byte[]> served = http.send(HttpRequest.newBuilder(URI.create(file.publicUrl())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).isEqualTo(JPEG);
        assertThat(storage.confirmUpload(ticket.fileId(), owner)).as("confirming twice changes nothing").isEqualTo(file);
    }

    @Test
    void theLinkOnlyAcceptsTheSizeAndTypeItWasIssuedFor() throws Exception {
        UUID owner = user();
        UploadTicket ticket = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));

        assertThat(put(ticket, jpeg(JPEG.length + 500))).as("a larger file than promised").isNotEqualTo(200);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
                        .header("Content-Type", "text/html")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(JPEG)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode())
                .as("a different content type than promised").isNotEqualTo(200);
        assertThatThrownBy(() -> storage.confirmUpload(ticket.fileId(), owner))
                .isInstanceOf(StorageException.UploadNotReceived.class);
    }

    @Test
    void aFileThatIsntReallyTheTypeItClaimsIsDeletedAndMarkedRejected() throws Exception {
        UUID owner = user();
        byte[] html = padded("<html><script>alert(document.cookie)</script></html>", JPEG.length);
        UploadTicket ticket = storage.requestUpload(publicPhoto(owner, "image/jpeg", html.length));
        assertThat(put(ticket, html)).isEqualTo(200);

        assertThatThrownBy(() -> storage.confirmUpload(ticket.fileId(), owner))
                .isInstanceOfSatisfying(StorageException.UploadRejected.class,
                        e -> assertThat(e.code()).isEqualTo("UPLOAD_CONTENT_MISMATCH"));

        // The rejection must survive the refusal (noRollbackFor), or the row says
        // pending while its object is already gone.
        assertThat(storage.find(ticket.fileId()).orElseThrow().status()).isEqualTo("rejected");
        assertThat(objectExists(PUBLIC_BUCKET, keyOf(ticket.fileId()))).isFalse();
    }

    @Test
    void aFileOfADifferentSizeThanPromisedIsRejected() {
        UUID owner = user();
        UploadTicket ticket = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));
        // Written straight to storage, around the link, to reach the check behind it.
        byte[] bigger = jpeg(JPEG.length + 10);
        admin.putObject(r -> r.bucket(PUBLIC_BUCKET).key(keyOf(ticket.fileId())).contentType("image/jpeg"),
                RequestBody.fromBytes(bigger));

        assertThatThrownBy(() -> storage.confirmUpload(ticket.fileId(), owner))
                .isInstanceOfSatisfying(StorageException.UploadRejected.class,
                        e -> assertThat(e.code()).isEqualTo("UPLOAD_SIZE_MISMATCH"));
        assertThat(objectExists(PUBLIC_BUCKET, keyOf(ticket.fileId()))).isFalse();
    }

    @Test
    void confirmingBeforeUploadingIsRefusedAndTheUploadCanStillFinish() throws Exception {
        UUID owner = user();
        UploadTicket ticket = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));

        assertThatThrownBy(() -> storage.confirmUpload(ticket.fileId(), owner))
                .isInstanceOf(StorageException.UploadNotReceived.class);
        put(ticket, JPEG);
        assertThat(storage.confirmUpload(ticket.fileId(), owner).ready()).isTrue();
    }

    @Test
    void onlyTheUploaderCanConfirm() throws Exception {
        UUID owner = user();
        UploadTicket ticket = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));
        put(ticket, JPEG);

        assertThatThrownBy(() -> storage.confirmUpload(ticket.fileId(), user()))
                .isInstanceOf(StorageException.FileNotFound.class);
        assertThat(storage.find(ticket.fileId()).orElseThrow().status()).isEqualTo("pending");
    }

    @Test
    void typesAndSizesOutsideTheRulesAreRefusedBeforeAnyLinkIsIssued() {
        UUID owner = user();
        assertThatThrownBy(() -> storage.requestUpload(publicPhoto(owner, "image/svg+xml", 100)))
                .isInstanceOf(StorageException.FileTypeNotAllowed.class);
        assertThatThrownBy(() -> storage.requestUpload(publicPhoto(owner, "image/gif", 100)))
                .isInstanceOf(StorageException.FileTypeNotAllowed.class);
        assertThatThrownBy(() -> storage.requestUpload(publicPhoto(owner, "application/pdf", 100)))
                .as("a type storage knows, but this caller doesn't allow")
                .isInstanceOf(StorageException.FileTypeNotAllowed.class);
        assertThatThrownBy(() -> storage.requestUpload(new UploadRequest(StorageVisibility.PUBLIC, "estate-photo",
                "a.jpg", "image/jpeg", 600_000, IMAGES, 500_000, owner, null)))
                .as("over the caller's limit").isInstanceOf(StorageException.FileTooLarge.class);
        assertThatThrownBy(() -> storage.requestUpload(new UploadRequest(StorageVisibility.PUBLIC, "estate-photo",
                "a.jpg", "image/jpeg", 2_000_000, IMAGES, Long.MAX_VALUE, owner, null)))
                .as("over the platform's limit (1MB here)").isInstanceOf(StorageException.FileTooLarge.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_files WHERE uploaded_by = ?", Long.class, owner))
                .isZero();
    }

    @Test
    void aPrivateFileIsNeverPublicAndIsReachedOnlyThroughAShortLivedLink() throws Exception {
        UUID owner = user();
        byte[] pdf = padded("%PDF-1.7\n% passport scan", 1024);
        UploadTicket ticket = storage.requestUpload(new UploadRequest(StorageVisibility.PRIVATE, "kyc-document",
                "My Passport.pdf", "application/pdf", pdf.length, EnumSet.of(FileType.PDF), 1_000_000, owner, null));
        put(ticket, pdf);
        StoredFileInfo file = storage.confirmUpload(ticket.fileId(), owner);

        assertThat(file.publicUrl()).isNull();
        String objectUrl = endpoint() + "/" + PRIVATE_BUCKET + "/" + keyOf(ticket.fileId());
        assertThat(http.send(HttpRequest.newBuilder(URI.create(objectUrl)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).as("anonymous read").isEqualTo(403);

        DownloadLink link = storage.downloadLink(ticket.fileId());
        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(URI.create(link.url())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(pdf);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
                .startsWith("attachment").contains("My Passport.pdf");
    }

    @Test
    void abandonedUploadsAreCleanedUpAndConfirmedOnesAreLeftAlone() throws Exception {
        UUID owner = user();
        UploadTicket abandoned = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));
        put(abandoned, JPEG);
        UploadTicket kept = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));
        put(kept, JPEG);
        storage.confirmUpload(kept.fileId(), owner);
        jdbc.update("UPDATE stored_files SET created_at = now() - interval '2 days' WHERE id IN (?, ?)",
                abandoned.fileId(), kept.fileId());

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(storage.find(abandoned.fileId()).orElseThrow().status()).isEqualTo("expired");
        assertThat(objectExists(PUBLIC_BUCKET, keyOf(abandoned.fileId()))).isFalse();
        assertThatThrownBy(() -> storage.confirmUpload(abandoned.fileId(), owner))
                .isInstanceOf(StorageException.UploadExpired.class);
        assertThat(storage.find(kept.fileId()).orElseThrow().ready()).isTrue();
        assertThat(objectExists(PUBLIC_BUCKET, keyOf(kept.fileId()))).isTrue();
    }

    @Test
    void deletingRemovesTheObjectAndKeepsTheRecord() throws Exception {
        UUID owner = user();
        UploadTicket ticket = storage.requestUpload(publicPhoto(owner, "image/jpeg", JPEG.length));
        put(ticket, JPEG);
        String url = storage.confirmUpload(ticket.fileId(), owner).publicUrl();

        storage.delete(ticket.fileId());
        storage.delete(ticket.fileId());

        assertThat(storage.find(ticket.fileId()).orElseThrow().status()).isEqualTo("deleted");
        assertThat(storage.find(ticket.fileId()).orElseThrow().publicUrl()).isNull();
        assertThat(http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> storage.downloadLink(ticket.fileId())).isInstanceOf(StorageException.FileNotReady.class);
    }

    // --- helpers -----------------------------------------------------------------

    private static UploadRequest publicPhoto(UUID owner, String contentType, long size) {
        return new UploadRequest(StorageVisibility.PUBLIC, "estate-photo", "site.jpg", contentType, size,
                IMAGES, 1_000_000, owner, null);
    }

    private int put(UploadTicket ticket, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
                .method(ticket.method(), HttpRequest.BodyPublishers.ofByteArray(body));
        ticket.headers().forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String keyOf(UUID fileId) {
        return jdbc.queryForObject("SELECT object_key FROM stored_files WHERE id = ?", String.class, fileId);
    }

    private static boolean objectExists(String bucket, String key) {
        try {
            admin.headObject(r -> r.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, created_at, created_by, deleted, first_name, last_name, email, password_hash,
                                   country, currency, status, two_fa_enabled)
                VALUES (?, now(), 'test', false, 'Up', 'Loader', ?, 'x', 'NG', 'NGN', 'ACTIVE', false)
                """, id, "uploader+" + id + "@example.com");
        return id;
    }

    /** A byte array that starts like a JPEG (FF D8 FF E0) and is this long. */
    private static byte[] jpeg(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 0x41);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    private static byte[] padded(String start, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) ' ');
        byte[] head = start.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }
}
