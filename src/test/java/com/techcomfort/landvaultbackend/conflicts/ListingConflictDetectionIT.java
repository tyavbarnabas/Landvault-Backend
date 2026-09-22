package com.techcomfort.landvaultbackend.conflicts;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.conflicts.dto.ConflictStatusRequest;
import com.techcomfort.landvaultbackend.conflicts.dto.ListingConflictDto;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Spatial conflict detection end to end, against a database where row-level
 * security is <strong>actually enforced</strong> — the app connects as the
 * restricted {@code landvault_app} role, not the container superuser.
 * <p>
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * Detection's whole premise is that it needs a deliberate escape from RLS:
 * it reads estates across tenants and writes to a platform-scope-only
 * table, from inside a tenant-scoped request. Under a superuser connection
 * there is nothing to escape from, so every test here would pass whether
 * the {@code SECURITY DEFINER} functions existed or not — which is exactly
 * how the tenant-staff login bug survived a green suite.
 * <p>
 * Boundaries are in Abuja and deliberately simple rectangles: the areas are
 * checkable by hand, so an assertion failing means the geometry is wrong
 * rather than the test being unreadable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ListingConflictDetectionIT {

    private static final String APP_ROLE = "landvault_app_conflicts_it";
    private static final String APP_ROLE_PASSWORD = "conflicts-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    /**
     * Each test method gets its own longitude band, 0.1° wide.
     * <p>
     * Not incidental tidiness — the first version of this class reused one
     * set of coordinates everywhere, so estates created by different tests
     * sat on top of each other and raised conflicts across test boundaries.
     * Per-pair assertions still passed, but anything counting <em>all</em>
     * conflicts on an estate (the publication check) saw the other tests'
     * estates too. Bands keep every test's geometry genuinely its own.
     * <p>
     * 0.1° is roughly 11 km, against an estate about 1 km wide, so no
     * fixture can reach its neighbour's band.
     */
    private static final AtomicInteger AREA_BAND = new AtomicInteger();

    private final BigDecimal bandLng = new BigDecimal("7.000")
            .add(new BigDecimal("0.100").multiply(BigDecimal.valueOf(AREA_BAND.getAndIncrement())));

    /** ~990m x 995m in Abuja, about 984,800 m². */
    private List<List<BigDecimal>> estateA() {
        return rect(lng("0.000"), "9.100", lng("0.009"), "9.109");
    }

    /** Shifted half a width east and north — overlaps A over 0.004° x 0.004°. */
    private List<List<BigDecimal>> estateBOverlapping() {
        return rect(lng("0.005"), "9.105", lng("0.014"), "9.114");
    }

    /** Shares A's eastern edge exactly. Intersects, but the shared area is a line. */
    private List<List<BigDecimal>> estateAdjacent() {
        return rect(lng("0.009"), "9.100", lng("0.018"), "9.109");
    }

    /** Nowhere near A. */
    private List<List<BigDecimal>> estateFar() {
        return rect(lng("0.030"), "9.200", lng("0.039"), "9.209");
    }

    /** A, moved east within its own band so it no longer touches B. */
    private String estateACorrectedWkt() {
        String x1 = lng("0.060");
        String x2 = lng("0.069");
        return "POLYGON((" + x1 + " 9.100, " + x2 + " 9.100, " + x2 + " 9.109, "
                + x1 + " 9.109, " + x1 + " 9.100))";
    }

    private List<List<BigDecimal>> plotOne() {
        return rect(lng("0.0010"), "9.1010", lng("0.0020"), "9.1020");
    }

    /** Overlaps {@link #plotOne()} over a quarter of its area — about 3,000 m². */
    private List<List<BigDecimal>> plotTwo() {
        return rect(lng("0.0015"), "9.1015", lng("0.0025"), "9.1025");
    }

    private String lng(String delta) {
        return bandLng.add(new BigDecimal(delta)).toPlainString();
    }

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

    /**
     * Injected so the CD-9 path can be exercised at all. Nothing in the
     * application triggers re-detection today — there is no endpoint that
     * updates a footprint — so auto-resolution is built but unreachable
     * through HTTP. Calling the API directly proves the logic works and
     * will fire the moment a correction endpoint exists, rather than
     * shipping it untested on a promise.
     */
    @Autowired
    private ConflictDetectionApi conflictDetection;

    // --- CD-1, CD-3: cross-company overlap ---

    @Test
    void twoCompaniesOverlappingBoundariesRaiseAHighSeverityConflict() {
        Tenant companyOne = tenant("Alpha Surveys");
        Tenant companyTwo = tenant("Beta Holdings");

        EstateDto first = createEstate(companyOne, "Alpha Fields", estateA());
        EstateDto second = createEstate(companyTwo, "Beta Gardens", estateBOverlapping());

        ListingConflictDto conflict = conflictFor(first.id(), second.id());

        assertThat(conflict.severity())
                .as("two different companies claiming the same ground is the fraud signal")
                .isEqualTo("high");
        assertThat(conflict.crossTenant()).isTrue();
        assertThat(conflict.status()).isEqualTo("open");
        assertThat(conflict.conflictType()).isEqualTo("estate_overlap");

        // 0.004 deg x 0.004 deg at latitude 9.1: about 440m x 442m.
        assertThat(conflict.overlapAreaSqm())
                .as("square metres via the geography cast, not square degrees")
                .isCloseTo(new BigDecimal("194500"), within(new BigDecimal("3000")));

        // Each estate is ~984,800 sqm, so ~194,500 of it is just under 20%.
        assertThat(conflict.overlapPctOfA().doubleValue()).isBetween(18.0, 22.0);
        assertThat(conflict.overlapPctOfB().doubleValue()).isBetween(18.0, 22.0);

        assertThat(List.of(conflict.tenantAId(), conflict.tenantBId()))
                .containsExactlyInAnyOrder(companyOne.id(), companyTwo.id());
        assertThat(List.of(conflict.tenantAName(), conflict.tenantBName()))
                .containsExactlyInAnyOrder(companyOne.name(), companyTwo.name());
    }

    @Test
    void oneCompanysOwnOverlappingEstatesAreOnlyMediumSeverity() {
        Tenant company = tenant("Gamma Estates");

        EstateDto first = createEstate(company, "Gamma North", estateA());
        EstateDto second = createEstate(company, "Gamma South", estateBOverlapping());

        ListingConflictDto conflict = conflictFor(first.id(), second.id());

        assertThat(conflict.severity())
                .as("a company's own survey error puts no buyer at risk of paying the wrong party")
                .isEqualTo("medium");
        assertThat(conflict.crossTenant()).isFalse();
    }

    // --- the threshold (no false positives) ---

    /**
     * The case that decides whether this feature is usable. Adjacent plots
     * and estates share edges by design; {@code ST_Intersects} is true for
     * boundary contact, so deciding on it would flag every correctly
     * surveyed neighbour and bury the real conflicts.
     */
    @Test
    void estatesSharingOnlyABoundaryLineRaiseNoConflict() {
        Tenant companyOne = tenant("Delta Land");
        Tenant companyTwo = tenant("Epsilon Land");

        EstateDto first = createEstate(companyOne, "Delta Plot", estateA());
        EstateDto second = createEstate(companyTwo, "Epsilon Plot", estateAdjacent());

        assertThat(intersects(first.id(), second.id()))
                .as("they really do touch — this is not a test that passes because nothing intersects")
                .isTrue();
        assertThat(conflictCountFor(first.id(), second.id()))
                .as("a shared edge has zero area and is below the sliver threshold")
                .isZero();
    }

    @Test
    void estatesNowhereNearEachOtherRaiseNoConflict() {
        Tenant company = tenant("Zeta Group");

        EstateDto first = createEstate(company, "Zeta One", estateA());
        EstateDto second = createEstate(company, "Zeta Two", estateFar());

        assertThat(conflictCountFor(first.id(), second.id())).isZero();
    }

    // --- CD-2: within-estate plots ---

    /**
     * The case AGENTS.md records as the <em>more common</em> scam, and which
     * was undetectable until plots carried geometry.
     */
    @Test
    void twoPlotsOverlappingInsideOneEstateAreDetected() {
        Tenant company = tenant("Eta Developments");
        EstateDto estate = createEstate(company, "Eta Park", estateA());
        UUID tierId = createTier(company, estate.id());
        UUID blockId = createBlock(company, estate.id());

        List<PlotDto> plots = createPlots(company, estate.id(), new CreatePlotsRequest(List.of(
                plot("1", blockId, tierId, plotOne()),
                plot("2", blockId, tierId, plotTwo()))));

        ListingConflictDto conflict = conflictFor(plots.get(0).id(), plots.get(1).id());

        assertThat(conflict.conflictType()).isEqualTo("plot_overlap");
        assertThat(conflict.severity())
                .as("both plots belong to one company by construction")
                .isEqualTo("medium");
        assertThat(conflict.estateId())
                .as("the containing estate, so the tenant view can find it")
                .isEqualTo(estate.id());
        // 0.0005 deg squared at latitude 9.1: about 55m x 55m.
        assertThat(conflict.overlapAreaSqm())
                .isCloseTo(new BigDecimal("3040"), within(new BigDecimal("150")));
        assertThat(conflict.estateAName()).startsWith("Block A / Plot ");
    }

    // --- CD-5: drafts included ---

    @Test
    void aDraftEstateOverlappingAPublishedOneIsStillDetected() {
        Tenant companyOne = tenant("Theta Ltd");
        Tenant companyTwo = tenant("Iota Ltd");

        EstateDto published = createEstate(companyOne, "Theta Live", estateA());
        // Publication has no endpoint yet; flip the flag directly so the
        // asymmetry this test is about actually exists.
        execute("UPDATE estates SET published = true, published_at = now() WHERE id = '" + published.id() + "'");

        EstateDto draft = createEstate(companyTwo, "Iota Draft", estateBOverlapping());
        assertThat(draft.published()).isFalse();

        assertThat(conflictFor(published.id(), draft.id()).severity())
                .as("catching this before the draft ever goes public is the entire point")
                .isEqualTo("high");
    }

    // --- CD-9: auto-resolution, and its deliberate limit ---

    /**
     * The gap that was found and closed before this shipped: geometry no
     * longer overlapping is not proof a cross-company dispute was fixed
     * rather than gamed — a boundary can be nudged just under the sliver
     * threshold while keeping almost all of the disputed ground. A HIGH
     * conflict must therefore not clear itself; only a human dismissing it
     * does. See AGENTS.md and the header comment on the detection
     * functions (changeset 047).
     */
    @Test
    void correctingAHighConflictsGeometryFlagsItForReviewButDoesNotLiftTheBlock() {
        Tenant companyOne = tenant("Kappa Survey");
        Tenant companyTwo = tenant("Lambda Survey");

        EstateDto first = createEstate(companyOne, "Kappa Block", estateA());
        EstateDto second = createEstate(companyTwo, "Lambda Block", estateBOverlapping());
        UUID conflictId = conflictFor(first.id(), second.id()).id();

        // Stands in for the footprint-correction endpoint that does not
        // exist yet — see the field comment on conflictDetection.
        execute("UPDATE estates SET footprint = ST_GeomFromText('" + estateACorrectedWkt()
                + "', 4326) WHERE id = '" + first.id() + "'");
        conflictDetection.detectForEstateBoundary(first.id());

        ListingConflictDto afterCorrection = adminGet(conflictId);
        assertThat(afterCorrection.status())
                .as("geometry clearing must not itself resolve a HIGH conflict")
                .isEqualTo("open");
        assertThat(afterCorrection.geometryClearedAt())
                .as("but the correction must be visible to whoever reviews it")
                .isNotNull();
        assertThat(afterCorrection.resolutionNote()).isNull();
        assertThat(afterCorrection.reviewedAt()).isNull();

        assertThat(conflictDetection.publicationCheckFor(first.id()).blocked())
                .as("the publication block must not lift on geometry alone")
                .isTrue();

        // A human's decision is what actually clears it.
        changeStatus(conflictId, new ConflictStatusRequest(
                "dismissed", "Reviewed the corrected survey — boundaries no longer conflict."));

        assertThat(adminGet(conflictId).status()).isEqualTo("dismissed");
        assertThat(conflictDetection.publicationCheckFor(first.id()).blocked()).isFalse();
    }

    /**
     * The other half of the same decision: a MEDIUM conflict — one
     * company's own survey error, nobody at risk of paying the wrong
     * party — keeps clearing itself exactly as CD-9 originally specified.
     * Requiring a human here would just be noise.
     */
    @Test
    void correctingAMediumConflictsGeometryStillAutoResolvesFully() {
        Tenant company = tenant("Xi Surveys");

        EstateDto first = createEstate(company, "Xi North", estateA());
        EstateDto second = createEstate(company, "Xi South", estateBOverlapping());
        UUID conflictId = conflictFor(first.id(), second.id()).id();

        execute("UPDATE estates SET footprint = ST_GeomFromText('" + estateACorrectedWkt()
                + "', 4326) WHERE id = '" + first.id() + "'");
        conflictDetection.detectForEstateBoundary(first.id());

        ListingConflictDto resolved = adminGet(conflictId);
        assertThat(resolved.status()).isEqualTo("auto_resolved");
        assertThat(resolved.resolutionNote()).contains("no longer overlaps");
        assertThat(resolved.overlapAreaSqm())
                .as("the record is kept, not deleted — including what the overlap was")
                .isPositive();
        assertThat(conflictDetection.publicationCheckFor(first.id()).warningConflictCount())
                .as("the warning clears along with the conflict")
                .isZero();
    }

    /**
     * The sharpest version of the gap: a human already looked and said
     * "this is a real duplicate." Geometry clearing after that must not
     * quietly overturn the decision either — it still takes a second,
     * deliberate human act (dismiss) to release it.
     */
    @Test
    void aConfirmedDuplicateStaysBlockedUntilAHumanDismissesItEvenAfterGeometryClears() {
        Tenant companyOne = tenant("Omicron Land");
        Tenant companyTwo = tenant("Pi Land");

        EstateDto first = createEstate(companyOne, "Omicron Block", estateA());
        EstateDto second = createEstate(companyTwo, "Pi Block", estateBOverlapping());
        UUID conflictId = conflictFor(first.id(), second.id()).id();

        changeStatus(conflictId, new ConflictStatusRequest("investigating", null));
        changeStatus(conflictId, new ConflictStatusRequest(
                "confirmed_duplicate", "Same survey plan filed with two different companies."));

        execute("UPDATE estates SET footprint = ST_GeomFromText('" + estateACorrectedWkt()
                + "', 4326) WHERE id = '" + first.id() + "'");
        conflictDetection.detectForEstateBoundary(first.id());

        ListingConflictDto afterCorrection = adminGet(conflictId);
        assertThat(afterCorrection.status())
                .as("a confirmed decision is not overturned by geometry alone")
                .isEqualTo("confirmed_duplicate");
        assertThat(afterCorrection.geometryClearedAt()).isNotNull();
        assertThat(conflictDetection.publicationCheckFor(first.id()).blocked()).isTrue();

        // The one exit CONFIRMED_DUPLICATE has: a human explicitly stands down.
        changeStatus(conflictId, new ConflictStatusRequest(
                "dismissed", "Reviewed the correction — the companies' boundaries no longer conflict."));

        assertThat(adminGet(conflictId).status()).isEqualTo("dismissed");
        assertThat(conflictDetection.publicationCheckFor(first.id()).blocked()).isFalse();

        // Found in the live walkthrough: the owner of a confirmed duplicate
        // that was dismissed after correction was told it had been "closed
        // as a false positive" — wrong, and a verdict their copy never
        // needed to make.
        String ownerView = restTemplate.exchange(
                "/api/portal/estates/" + first.id() + "/conflicts", HttpMethod.GET,
                new HttpEntity<>(bearer(companyOne.token())), String.class).getBody();
        assertThat(ownerView)
                .contains("closed after the boundary was corrected")
                .doesNotContain("false positive");
    }

    /**
     * Found in the live walkthrough: after a dismissed conflict's boundary
     * was broken again, the tenant's list showed the closed record above the
     * new open conflict actually pausing publication — they tied on severity
     * and area, so the order was arbitrary. Live must come first, in both
     * the tenant view and the admin queue.
     */
    @Test
    void aLiveConflictIsListedAboveAClosedRecordForTheSamePair() {
        Tenant companyOne = tenant("Tau Survey");
        Tenant companyTwo = tenant("Upsilon Survey");

        EstateDto first = createEstate(companyOne, "Tau Block", estateA());
        EstateDto second = createEstate(companyTwo, "Upsilon Block", estateBOverlapping());
        UUID dismissedId = conflictFor(first.id(), second.id()).id();

        execute("UPDATE estates SET footprint = ST_GeomFromText('" + estateACorrectedWkt()
                + "', 4326) WHERE id = '" + first.id() + "'");
        conflictDetection.detectForEstateBoundary(first.id());
        changeStatus(dismissedId, new ConflictStatusRequest("dismissed", "Correction reviewed."));

        // Broken again: back onto the other company's ground. Since
        // changeset 048 this also pins the revert-after-clearance case: the
        // dismissal above was made AFTER the geometry cleared
        // (geometry_cleared_at set), so it accepted a correction, not an
        // overlap — putting the boundary back must raise a NEW conflict,
        // never be swallowed by "the dismissal stands".
        String original = "POLYGON((" + lng("0.000") + " 9.100, " + lng("0.009") + " 9.100, "
                + lng("0.009") + " 9.109, " + lng("0.000") + " 9.109, " + lng("0.000") + " 9.100))";
        execute("UPDATE estates SET footprint = ST_GeomFromText('" + original
                + "', 4326) WHERE id = '" + first.id() + "'");
        conflictDetection.detectForEstateBoundary(first.id());

        List<TenantConflictView> tenantView = restTemplate.exchange(
                "/api/portal/estates/" + first.id() + "/conflicts", HttpMethod.GET,
                new HttpEntity<>(bearer(companyOne.token())),
                new ParameterizedTypeReference<List<TenantConflictView>>() {
                }).getBody();
        assertThat(tenantView).hasSize(2);
        assertThat(tenantView.get(0).status()).as("the live conflict comes first").isEqualTo("open");
        assertThat(tenantView.get(1).id()).isEqualTo(dismissedId);

        List<ListingConflictDto> forPair = adminList().stream()
                .filter(c -> matchesPair(c, first.id(), second.id()))
                .toList();
        assertThat(forPair).hasSize(2);
        assertThat(forPair.get(0).status()).isEqualTo("open");
        assertThat(forPair.get(1).id()).isEqualTo(dismissedId);
    }

    /**
     * A reviewer decides two estates sharing a strip along a road is fine
     * and dismisses it, touching no boundary. That decision must survive a
     * re-scan — otherwise "dismiss" is meaningless for anything short of a
     * boundary correction, and the reviewer is back to square one forever.
     * Changeset 048.
     */
    @Test
    void aDismissalOfAnUnchangedOverlapStandsAcrossReScans() {
        Tenant companyOne = tenant("Phi Estates");
        Tenant companyTwo = tenant("Chi Estates");

        EstateDto first = createEstate(companyOne, "Phi Block", estateA());
        EstateDto second = createEstate(companyTwo, "Chi Block", estateBOverlapping());
        UUID dismissedId = conflictFor(first.id(), second.id()).id();

        changeStatus(dismissedId, new ConflictStatusRequest(
                "dismissed", "Shared access strip along the road; both surveys are correct."));

        // Nothing changed; both sides re-scanned.
        conflictDetection.detectForEstateBoundary(first.id());
        conflictDetection.detectForEstateBoundary(second.id());

        assertThat(conflictCountFor(first.id(), second.id()))
                .as("the dismissal stands — no fresh conflict for an unchanged overlap")
                .isEqualTo(1);
        assertThat(adminGet(dismissedId).status()).isEqualTo("dismissed");
        assertThat(conflictDetection.publicationCheckFor(first.id()).blocked()).isFalse();
    }

    /**
     * The other half: the dismissal only covers the overlap it was made
     * against. A materially different overlap is a different situation and
     * gets looked at again.
     */
    @Test
    void aDismissedOverlapThatChangesRaisesAFreshConflict() {
        Tenant companyOne = tenant("Psi Estates");
        Tenant companyTwo = tenant("Omega Estates");

        EstateDto first = createEstate(companyOne, "Psi Block", estateA());
        EstateDto second = createEstate(companyTwo, "Omega Block", estateBOverlapping());
        UUID dismissedId = conflictFor(first.id(), second.id()).id();
        changeStatus(dismissedId, new ConflictStatusRequest("dismissed", "Acceptable as surveyed."));

        // The second estate creeps further onto the first: overlap goes from
        // 0.004 deg square to 0.006 deg square — about 2.25x the area.
        String grown = "POLYGON((" + lng("0.003") + " 9.103, " + lng("0.012") + " 9.103, "
                + lng("0.012") + " 9.112, " + lng("0.003") + " 9.112, " + lng("0.003") + " 9.103))";
        execute("UPDATE estates SET footprint = ST_GeomFromText('" + grown
                + "', 4326) WHERE id = '" + second.id() + "'");
        conflictDetection.detectForEstateBoundary(second.id());

        List<ListingConflictDto> forPair = adminList().stream()
                .filter(c -> matchesPair(c, first.id(), second.id()))
                .toList();
        assertThat(forPair).as("a fresh conflict beside the kept dismissal").hasSize(2);
        assertThat(forPair.get(0).status()).isEqualTo("open");
        assertThat(forPair.get(0).overlapAreaSqm())
                .as("and it carries the new, larger overlap")
                .isGreaterThan(adminGet(dismissedId).overlapAreaSqm().multiply(new BigDecimal("2")));
        assertThat(conflictDetection.publicationCheckFor(first.id()).blocked()).isTrue();
    }

    /** Only the fields this test reads — the full DTO lives in conflicts.dto. */
    private record TenantConflictView(UUID id, String status) {
    }

    /** The transition CONFIRMED_DUPLICATE otherwise refuses every other target. */
    @Test
    void aConfirmedDuplicateCannotBeMovedAnywhereOtherThanDismissed() {
        Tenant companyOne = tenant("Rho Land");
        Tenant companyTwo = tenant("Sigma Land");

        EstateDto first = createEstate(companyOne, "Rho Block", estateA());
        EstateDto second = createEstate(companyTwo, "Sigma Block", estateBOverlapping());
        UUID conflictId = conflictFor(first.id(), second.id()).id();

        changeStatus(conflictId, new ConflictStatusRequest("investigating", null));
        changeStatus(conflictId, new ConflictStatusRequest("confirmed_duplicate", "Confirmed."));

        ResponseEntity<String> backToInvestigating = restTemplate.exchange(
                "/api/admin/listing-conflicts/" + conflictId + "/status", HttpMethod.POST,
                entity(adminToken(), new ConflictStatusRequest("investigating", null)), String.class);
        assertThat(backToInvestigating.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> reConfirm = restTemplate.exchange(
                "/api/admin/listing-conflicts/" + conflictId + "/status", HttpMethod.POST,
                entity(adminToken(), new ConflictStatusRequest("confirmed_duplicate", "Again.")), String.class);
        assertThat(reConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- CD-8: a re-scan must not destroy work in progress ---

    @Test
    void reRunningDetectionKeepsAnInvestigatingConflictIntactAndUnduplicated() {
        Tenant companyOne = tenant("Mu Properties");
        Tenant companyTwo = tenant("Nu Properties");

        EstateDto first = createEstate(companyOne, "Mu Site", estateA());
        EstateDto second = createEstate(companyTwo, "Nu Site", estateBOverlapping());
        UUID conflictId = conflictFor(first.id(), second.id()).id();

        changeStatus(conflictId, new ConflictStatusRequest("investigating", null));
        conflictDetection.detectForEstateBoundary(first.id());

        assertThat(conflictCountFor(first.id(), second.id()))
                .as("the same pair must not produce a second row")
                .isEqualTo(1);
        ListingConflictDto after = adminGet(conflictId);
        assertThat(after.id()).as("and must keep its identity").isEqualTo(conflictId);
        assertThat(after.status())
                .as("a Super Admin halfway through an investigation must not be reset to open")
                .isEqualTo("investigating");
    }

    // --- CD-11: non-disclosure ---

    /**
     * Asserted against the raw serialized body, not the DTO's accessors — a
     * field that leaked through a custom serializer, an unexpected getter,
     * or a future {@code @JsonAnyGetter} would pass a shape-level check and
     * fail this one.
     */
    @Test
    void theTenantViewNeverRevealsTheOtherCompany() {
        Tenant companyOne = tenant("Xi Ventures");
        Tenant companyTwo = tenant("Omicron Ventures");

        EstateDto mine = createEstate(companyOne, "Xi Estate", estateA());
        EstateDto theirs = createEstate(companyTwo, "Omicron Estate", estateBOverlapping());

        ResponseEntity<String> raw = restTemplate.exchange(
                "/api/portal/estates/" + mine.id() + "/conflicts",
                HttpMethod.GET, new HttpEntity<>(bearer(companyOne.token())), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = raw.getBody();

        assertThat(body).as("the conflict itself is their business and must be shown").contains("high");
        assertThat(body).contains("Xi Estate");
        assertThat(body).doesNotContain(companyTwo.name());
        assertThat(body).doesNotContain(companyTwo.id().toString());
        assertThat(body).doesNotContain(theirs.id().toString());
        assertThat(body).doesNotContain("Omicron");
        assertThat(body)
                .as("tone: factual and solution-oriented, never an accusation")
                .contains("survey coordinates");
    }

    @Test
    void aTenantSeesNoConflictsOnAnotherTenantsEstate() {
        Tenant companyOne = tenant("Pi Estates");
        Tenant companyTwo = tenant("Rho Estates");

        EstateDto mine = createEstate(companyOne, "Pi Estate", estateA());
        createEstate(companyTwo, "Rho Estate", estateBOverlapping());

        ResponseEntity<String> theirView = restTemplate.exchange(
                "/api/portal/estates/" + mine.id() + "/conflicts",
                HttpMethod.GET, new HttpEntity<>(bearer(companyTwo.token())), String.class);

        assertThat(theirView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theirView.getBody())
                .as("an empty list, not a 403 — a 403 would confirm the estate exists")
                .isEqualTo("[]");
    }

    // --- authorization ---

    @Test
    void aBuyerCannotReachTheAdminQueue() {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        register(email);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/listing-conflicts", HttpMethod.GET,
                new HttpEntity<>(bearer(login(email))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- CD-10: publication consequence ---

    /**
     * Two tests rather than one, because each test method owns a longitude
     * band and these two scenarios need estates that cannot see each other.
     * Building both in one method put four estates in one band, where the
     * "own survey error" pair overlapped the cross-tenant pair and the
     * check correctly reported a block — for the wrong reason.
     */
    @Test
    void aHighConflictBlocksPublication() {
        Tenant companyOne = tenant("Sigma Land");
        Tenant companyTwo = tenant("Tau Land");

        EstateDto subject = createEstate(companyOne, "Sigma Site", estateA());
        createEstate(companyTwo, "Tau Site", estateBOverlapping());

        ConflictPublicationCheck check = conflictDetection.publicationCheckFor(subject.id());

        assertThat(check.blocked()).isTrue();
        assertThat(check.blockingConflictCount()).isEqualTo(1);
        assertThat(check.warningConflictCount()).isZero();
        assertThat(check.blockReason()).contains("Publication is paused");
    }

    @Test
    void aMediumConflictWarnsButDoesNotBlock() {
        Tenant company = tenant("Upsilon Land");

        EstateDto subject = createEstate(company, "Upsilon One", estateA());
        createEstate(company, "Upsilon Two", estateBOverlapping());

        ConflictPublicationCheck check = conflictDetection.publicationCheckFor(subject.id());

        assertThat(check.blocked())
                .as("a company's own survey error puts no buyer at risk, so blocking trade "
                        + "over it would be disproportionate")
                .isFalse();
        assertThat(check.warningConflictCount()).isEqualTo(1);
        assertThat(check.blockingConflictCount()).isZero();
        assertThat(check.blockReason()).isNull();
    }

    // --- performance shape ---

    /**
     * The detection predicate must use the GiST index rather than compare
     * every estate against every other. Verified against a table with
     * enough rows for the planner to have a real choice — on a handful of
     * rows Postgres correctly prefers a sequential scan, and a plan taken
     * there would prove nothing.
     */
    @Test
    void detectionUsesTheGistIndexRatherThanASequentialScan() {
        Tenant company = tenant("Phi Analytics");
        EstateDto estate = createEstate(company, "Phi Subject", estateA());

        // Far from every other fixture in this class, so these rows cannot
        // create conflicts of their own.
        execute("""
                INSERT INTO estates (id, created_at, deleted, tenant_id, branch_id,
                                     name, slug, published, footprint)
                SELECT gen_random_uuid(), now(), false, '%s', '%s',
                       'Bulk ' || g, 'bulk-' || g, false,
                       ST_SetSRID(ST_MakeEnvelope(
                           5.0 + (g %% 100) * 0.01,
                           5.0 + (g / 100) * 0.01,
                           5.0 + (g %% 100) * 0.01 + 0.004,
                           5.0 + (g / 100) * 0.01 + 0.004), 4326)
                FROM generate_series(1, 3000) g
                """.formatted(company.id(), company.branchId()));
        execute("ANALYZE estates");

        String plan = explain("""
                SELECT count(*)
                FROM estates s
                JOIN estates e ON e.id <> s.id AND ST_Intersects(s.footprint, e.footprint)
                WHERE s.id = '%s'
                """.formatted(estate.id()));

        assertThat(plan)
                .as("the GiST index must narrow candidates; exact geometry then decides. Plan was:\\n" + plan)
                .contains("idx_estates_footprint");
        assertThat(plan).doesNotContain("Seq Scan on estates e");
    }

    // --- helpers ---

    private record Tenant(UUID id, String name, UUID branchId, String token) {
    }

    private Tenant tenant(String namePrefix) {
        String rc = "RC-" + UUID.randomUUID();
        String registeredName = namePrefix + " " + rc;
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto(registeredName, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("A Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");

        ResponseEntity<TenantDetailDto> created = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken(), request), TenantDetailDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = created.getBody().id();

        UUID branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenantId + "', 'Head Office')");

        String email = "ed+" + UUID.randomUUID() + "@example.com";
        register(email);
        execute("UPDATE users SET tenant_id = '" + tenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = "
                + "(SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = 'executive_director'");

        return new Tenant(tenantId, registeredName, branchId, login(email));
    }

    private EstateDto createEstate(Tenant tenant, String name, List<List<BigDecimal>> ring) {
        CreateEstateRequest request = new CreateEstateRequest(
                name, null, "Gwarinpa", "Abuja", "FCT", "1 Test Close", new BigDecimal("10.00"),
                null, List.of(), tenant.branchId(), new GeoJsonPolygonDto("Polygon", List.of(ring)));
        ResponseEntity<EstateDto> response = restTemplate.exchange(
                "/api/portal/estates", HttpMethod.POST, entity(tenant.token(), request), EstateDto.class);
        assertThat(response.getStatusCode()).as("creating " + name).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private UUID createTier(Tenant tenant, UUID estateId) {
        ResponseEntity<PriceTierDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers", HttpMethod.POST,
                entity(tenant.token(), new CreatePriceTierRequest(
                        "LAND_SIZE", new BigDecimal("3000.00"), new BigDecimal("20000000.0000"),
                        Currency.NGN, "Standard")),
                PriceTierDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private UUID createBlock(Tenant tenant, UUID estateId) {
        ResponseEntity<BlockDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/blocks", HttpMethod.POST,
                entity(tenant.token(), new CreateBlockRequest("A", "Block A")), BlockDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private List<PlotDto> createPlots(Tenant tenant, UUID estateId, CreatePlotsRequest request) {
        ResponseEntity<List<PlotDto>> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots", HttpMethod.POST,
                entity(tenant.token(), request), new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static CreatePlotRequest plot(String number, UUID blockId, UUID tierId, List<List<BigDecimal>> ring) {
        return new CreatePlotRequest(number, blockId, tierId, false, "available-dev",
                null, null, null, null, null, new GeoJsonPolygonDto("Polygon", List.of(ring)));
    }

    /** The one conflict recorded for this pair, via the admin queue. */
    private ListingConflictDto conflictFor(UUID entityOne, UUID entityTwo) {
        List<ListingConflictDto> matches = adminList().stream()
                .filter(conflict -> matchesPair(conflict, entityOne, entityTwo))
                .toList();
        assertThat(matches).as("expected exactly one conflict for this pair").hasSize(1);
        return matches.getFirst();
    }

    private int conflictCountFor(UUID entityOne, UUID entityTwo) {
        return (int) adminList().stream().filter(c -> matchesPair(c, entityOne, entityTwo)).count();
    }

    private static boolean matchesPair(ListingConflictDto conflict, UUID one, UUID two) {
        return (conflict.estateAId().equals(one) && conflict.estateBId().equals(two))
                || (conflict.estateAId().equals(two) && conflict.estateBId().equals(one));
    }

    private List<ListingConflictDto> adminList() {
        return restTemplate.exchange(
                "/api/admin/listing-conflicts?limit=100", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken())),
                new ParameterizedTypeReference<PageResponse<ListingConflictDto>>() {
                }).getBody().items();
    }

    private ListingConflictDto adminGet(UUID conflictId) {
        ResponseEntity<ListingConflictDto> response = restTemplate.exchange(
                "/api/admin/listing-conflicts/" + conflictId, HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken())), ListingConflictDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void changeStatus(UUID conflictId, ConflictStatusRequest request) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/listing-conflicts/" + conflictId + "/status", HttpMethod.POST,
                entity(adminToken(), request), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void register(String email) {
        RegisterRequest request = new RegisterRequest(
                "Test", "User", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private String adminToken() {
        return login(ADMIN_EMAIL);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** A closed rectangle in GeoJSON [lng, lat] order. */
    private static List<List<BigDecimal>> rect(String minLng, String minLat, String maxLng, String maxLat) {
        BigDecimal x1 = new BigDecimal(minLng);
        BigDecimal y1 = new BigDecimal(minLat);
        BigDecimal x2 = new BigDecimal(maxLng);
        BigDecimal y2 = new BigDecimal(maxLat);
        return List.of(
                List.of(x1, y1), List.of(x2, y1), List.of(x2, y2), List.of(x1, y2), List.of(x1, y1));
    }

    // Superuser connection, deliberately: fixture setup writes rows the
    // app's own restricted role could not, and reads plans it cannot.
    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean intersects(UUID estateOne, UUID estateTwo) {
        return "t".equals(querySingle(
                "SELECT ST_Intersects(a.footprint, b.footprint) FROM estates a, estates b "
                        + "WHERE a.id = '" + estateOne + "' AND b.id = '" + estateTwo + "'"));
    }

    private static String explain(String sql) {
        StringBuilder plan = new StringBuilder();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return plan.toString();
    }

    private static String querySingle(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
