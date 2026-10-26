package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.testsupport.EstatePhotoFixture;
import com.techcomfort.landvaultbackend.testsupport.LetterApprovalFixture;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateEligibilityDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.FeeScheduleDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceListingDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplacePriceTierDto;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full cost disclosure, against a database where row-level security is
 * actually enforced.
 * <p>
 * The figures here are the real ones. <strong>Double King Estate:</strong>
 * ₦6,000,000 of land, ₦4,010,000 of charges, a true commitment of
 * ₦10,010,000 — 67% above the advertised price. <strong>Top Rank Platinum
 * City:</strong> ₦4,500,000 of land against ₦7,000,000 of infrastructure
 * alone, ₦12,610,000 in total, nearly three times what was advertised.
 * <p>
 * Both companies disclosed every term, and both appear to operate legally.
 * The failure was sequence — the charges arrived after the money had. These
 * tests exist to pin the arithmetic that moves them earlier.
 * <p>
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * A fee schedule is a developer's commercial position; the isolation
 * assertion below would pass whether changeset 058's policies exist or not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class FullCostDisclosureUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_disclosure_it";
    private static final String APP_ROLE_PASSWORD = "disclosure-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";

    private static final BigDecimal DOUBLE_KING_LAND = new BigDecimal("6000000.0000");
    private static final BigDecimal TOP_RANK_LAND = new BigDecimal("4500000.0000");

    private static final AtomicInteger BAND = new AtomicInteger();
    /** 30% deposit, 12 monthly instalments, 10% premium — the shape agreed with the user. */
    private static final String TWELVE_MONTH_PLAN = """
            [{"name":"12-month plan","depositPct":30,"instalmentCount":12,"frequency":"monthly","premiumPct":10}]
            """;

    /** 20% to start, ₦100,000 minimum, 15% of what was paid kept on leaving, 40% of any gain kept, 10% premium. */
    private static final String FLEXIBLE_PLAN = """
            {"name":"Pay as you can","kind":"flexible","depositPct":20,"premiumPct":10,
             "flexible":{"minimumContribution":100000,"exit":{"deductionPct":15,"appliesTo":"amount_paid"},
                         "developerGainSharePct":40,"inactivityMonths":6,"noticeDays":30,"maxMonths":36}}
            """;

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

    // ------------------------------------------------------------------
    // The mechanism: disclosure as a publication condition
    // ------------------------------------------------------------------

    /**
     * The whole epic in one assertion. "Please disclose your fees" is a
     * policy a developer ignores; this is the version that holds.
     */
    @Test
    void anEstateCannotBeListedUntilItsFeesAreDeclared() {
        Developer developer = verifiedDeveloper();
        UUID estateId = estateWithATier(developer, DOUBLE_KING_LAND).estateId();

        ResponseEntity<String> refused = publish(developer, estateId);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("PUBLICATION_FEES_UNDECLARED");
        assertThat(feedNames()).doesNotContain(estateName(estateId));
    }

    /**
     * An estate with genuinely no extra charges must be able to list — but
     * by saying so. Silence and "nothing to declare" are different facts,
     * and only one of them is a disclosure.
     */
    @Test
    void declaringAnEmptyScheduleIsADeclarationAndUnblocksPublication() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, DOUBLE_KING_LAND);

        FeeScheduleDto schedule = declareFees(developer, listing.estateId(), "[]");
        assertThat(schedule.fees()).isEmpty();
        assertThat(schedule.declaredAt()).as("the declaration itself is the fact recorded").isNotNull();

        declareTerms(developer, listing.estateId());
        assertThat(publish(developer, listing.estateId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(feedNames()).contains(estateName(listing.estateId()));
    }

    /**
     * Estates already on the marketplace when disclosure shipped keep their
     * listing. Delisting real listings to make a point would be principle
     * over usefulness; changeset 056 grandfathers them and carries a TODO to
     * backfill and tighten.
     */
    @Test
    void anEstatePublishedBeforeTheRequirementStaysListed() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, DOUBLE_KING_LAND);

        // Exactly what changeset 056's one-time UPDATE does for a row that
        // predates the rule: published, exempt, and nothing ever declared.
        // Live then means live when 086 ran too, so it is photo-exempt as well.
        execute("UPDATE estates SET published = true, published_at = now(), "
                + "fees_declaration_exempt = true, photo_requirement_exempt = true "
                + "WHERE id = '" + listing.estateId() + "'");
        // An approved letter has no exemption (changeset 095): without one, nothing on it can be allocated.
        execute(LetterApprovalFixture.approvedLetterSql(listing.estateId()));

        assertThat(feedNames())
                .as("a live listing must not go dark because a new rule arrived")
                .contains(estateName(listing.estateId()));
        assertThat(listingFor(listing.estateId()).costDisclosure())
                .as("and it honestly reports nothing rather than inventing an empty schedule")
                .isNull();
    }

    // ------------------------------------------------------------------
    // The arithmetic, against the real letters
    // ------------------------------------------------------------------

    @Test
    void doubleKingsTrueCommitmentIsTenMillionAndTenThousand() {
        Listing listing = publishedListingWith(DOUBLE_KING_LAND, doubleKingFees());

        MarketplacePriceTierDto tier = listingFor(listing.estateId()).priceTiers().getFirst();

        assertThat(tier.price()).isEqualByComparingTo(DOUBLE_KING_LAND);
        assertThat(tier.commitment().oneOffFees().min()).isEqualByComparingTo("4010000.0000");
        assertThat(tier.commitment().totalCommitment().min())
                .as("₦6,000,000 advertised, ₦10,010,000 actually committed — 67% more")
                .isEqualByComparingTo("10010000.0000");
        assertThat(tier.commitment().hasAdditionalCost()).isTrue();
    }

    @Test
    void topRanksTrueCommitmentIsTwelveMillionSixHundredAndTenThousand() {
        Listing listing = publishedListingWith(TOP_RANK_LAND, topRankFees());

        MarketplacePriceTierDto tier = listingFor(listing.estateId()).priceTiers().getFirst();

        assertThat(tier.commitment().totalCommitment().min())
                .as("₦4,500,000 advertised, ₦12,610,000 actually committed — nearly three times")
                .isEqualByComparingTo("12610000.0000");
    }

    /**
     * Both letters name an annual facility fee, and neither includes it in
     * the total they state. Folding one year of a perpetual charge into a
     * purchase price would be arbitrary — which year? — and would misstate
     * both the purchase and the obligation.
     */
    @Test
    void anAnnualFeeIsReportedSeparatelyRatherThanFoldedIntoTheTotal() {
        Listing listing = publishedListingWith(DOUBLE_KING_LAND, doubleKingFees());

        MarketplacePriceTierDto tier = listingFor(listing.estateId()).priceTiers().getFirst();

        assertThat(tier.commitment().recurringFees().min()).isEqualByComparingTo("150000.0000");
        assertThat(tier.commitment().totalCommitment().min())
                .as("the recurring charge is disclosed, but not added to the purchase total")
                .isEqualByComparingTo("10010000.0000");
    }

    /**
     * A variable fee stays a range all the way to the buyer. A midpoint is a
     * figure nobody quoted and nobody is bound by.
     */
    @Test
    void aVariableFeeIsPublishedAsARangeNeverAsAMidpoint() {
        Listing listing = publishedListingWith(TOP_RANK_LAND, """
                [{"feeType":"infrastructure","amountMin":7000000.0000,"amountMax":9000000.0000,
                  "currency":"NGN","isFixed":false,
                  "variationBasis":"Subject to change due to fluctuations in the prices of building materials.",
                  "dueTrigger":"on_construction_start","refundable":false,"isMandatory":true}]
                """);

        MarketplacePriceTierDto tier = listingFor(listing.estateId()).priceTiers().getFirst();

        assertThat(tier.commitment().totalCommitment().min()).isEqualByComparingTo("11500000.0000");
        assertThat(tier.commitment().totalCommitment().max()).isEqualByComparingTo("13500000.0000");
        assertThat(tier.commitment().totalCommitment().isRange()).isTrue();
        assertThat(listingFor(listing.estateId()).costDisclosure().fees().getFirst().variationBasis())
                .as("a fee may vary, but never silently")
                .contains("building materials");
    }

    @Test
    void aVariableFeeWithNoStatedBasisForVariationIsRefused() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, TOP_RANK_LAND);

        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/portal/estates/" + listing.estateId() + "/fees", HttpMethod.PUT,
                entity(developer.token(), """
                        {"fees":[{"feeType":"infrastructure","amountMin":7000000,"amountMax":9000000,
                          "currency":"NGN","isFixed":false,"dueTrigger":"on_construction_start",
                          "refundable":false,"isMandatory":true}]}
                        """), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("variationBasis");
    }

    /**
     * Adding a dollar fee to a naira price produces a number that is wrong in
     * every currency. The charge is still disclosed; it is just never summed.
     */
    @Test
    void aFeeInAnotherCurrencyIsDisclosedButNeverSummedIntoTheTotal() {
        Listing listing = publishedListingWith(DOUBLE_KING_LAND, """
                [{"feeType":"legal","amount":2000.0000,"currency":"USD","isFixed":true,
                  "dueTrigger":"at_allocation","refundable":false,"isMandatory":true}]
                """);

        MarketplaceListingDto published = listingFor(listing.estateId());
        MarketplacePriceTierDto tier = published.priceTiers().getFirst();

        assertThat(tier.commitment().totalCommitment().min())
                .as("the naira total is the land price alone — the dollar fee is not converted")
                .isEqualByComparingTo(DOUBLE_KING_LAND);
        assertThat(tier.commitment().totalExcludesOtherCurrencyFees())
                .as("and the omission is stated, not silent")
                .isTrue();
        assertThat(published.costDisclosure().fees())
                .as("the charge is still disclosed in full")
                .anySatisfy(fee -> assertThat(fee.currency()).isEqualTo("USD"));
    }

    // ------------------------------------------------------------------
    // Leaving
    // ------------------------------------------------------------------

    @Test
    void withdrawingIsPricedInNairaAndNonRefundableFeesAreExcludedFromWhatComesBack() {
        Listing listing = publishedListingWith(TOP_RANK_LAND, topRankFees());

        var exit = listingFor(listing.estateId()).costDisclosure().exitCosts();

        assertThat(exit.ifYouWithdraw().deduction()).isEqualByComparingTo("900000.0000");
        assertThat(exit.ifYouWithdraw().refundAmount())
                .as("20% of ₦4,500,000 withheld")
                .isEqualByComparingTo("3600000.0000");
        assertThat(exit.ifYouWithdraw().nonRefundableFees())
                .as("the application fee never comes back and is counted as lost, not netted away")
                .isEqualByComparingTo("10000.0000");
        assertThat(exit.ifYouWithdraw().totalLoss()).isEqualByComparingTo("910000.0000");
        assertThat(exit.ifYouWithdraw().processingDays()).isEqualTo(90);
    }

    /** Decided with the user: a penalty is a share of the overdue instalment, not of the whole price. */
    @Test
    void latePaymentPenaltiesArePublishedAsAmountsOnOneInstalment() {
        Listing listing = publishedListingWith(TOP_RANK_LAND, topRankFees(), TWELVE_MONTH_PLAN);

        var exit = listingFor(listing.estateId()).costDisclosure().exitCosts();
        var steps = exit.ifYouFallBehind();

        assertThat(exit.penaltyBasisPlan()).isEqualTo("12-month plan");
        assertThat(exit.penaltyBasisInstalment()).as("(₦4,950,000 − ₦1,485,000) ÷ 12").isEqualByComparingTo("288750");
        assertThat(steps).extracting(s -> s.monthsLate()).containsExactly(3, 6, 12);
        assertThat(steps.get(0).amount()).isEqualByComparingTo("14437.50");
        assertThat(steps.get(1).amount()).isEqualByComparingTo("28875");
        assertThat(steps.get(2).amount()).as("20% of one instalment").isEqualByComparingTo("57750");
    }

    @Test
    void anOutrightOnlyEstateHasNothingToFallBehindOn() {
        Listing listing = publishedListingWith(TOP_RANK_LAND, topRankFees());

        MarketplaceListingDto found = listingFor(listing.estateId());
        assertThat(found.costDisclosure().exitCosts().ifYouFallBehind()).isEmpty();
        assertThat(found.costDisclosure().exitCosts().penaltyBasisPlan()).isNull();
        assertThat(found.priceTiers().getFirst().paymentPlans()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Instalment plans (PL-1, PL-2)
    // ------------------------------------------------------------------

    /** The agreed rules: the premium on the whole price, the deposit a share of that total, worked out per tier. */
    @Test
    void declaredPlansAreWorkedOutByHomeTrueAndPublishedPerTier() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, TOP_RANK_LAND);
        declareFees(developer, listing.estateId(), topRankFees());
        declareTerms(developer, listing.estateId());
        assertThat(publish(developer, listing.estateId()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> declared = declarePlans(developer, listing.estateId(), TWELVE_MONTH_PLAN);
        assertThat(declared.getStatusCode()).as(declared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(declared.getBody()).contains("\"version\":1", "\"planTotal\":4950000",
                "\"deposit\":1485000", "\"instalment\":288750", "\"premium\":450000");

        var plan = listingFor(listing.estateId()).priceTiers().getFirst().paymentPlans().getFirst();
        assertThat(plan.name()).isEqualTo("12-month plan");
        assertThat(plan.frequency()).isEqualTo("monthly");
        assertThat(plan.planTotal()).isEqualByComparingTo("4950000");
        assertThat(plan.deposit()).isEqualByComparingTo("1485000");
        assertThat(plan.instalmentCount()).isEqualTo(12);
        assertThat(plan.instalment()).isEqualByComparingTo("288750");
        assertThat(plan.premium()).isEqualByComparingTo("450000");
        assertThat(plan.ifAnInstalmentIsLate()).extracting(s -> s.monthsLate()).containsExactly(3, 6, 12);

        assertThat(query("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.payment_plans_declared' "
                + "AND target_id = '" + listing.estateId() + "'")).isEqualTo("1");
    }

    @Test
    void redeclaringPlansWritesANewVersionAndAnEmptyListMeansOutrightOnly() {
        Developer developer = verifiedDeveloper();
        Listing listing = publishedListingWith(developer, TOP_RANK_LAND, topRankFees(), TWELVE_MONTH_PLAN);

        ResponseEntity<String> outright = declarePlans(developer, listing.estateId(), "[]");
        assertThat(outright.getBody()).contains("\"version\":2", "\"plans\":[]");
        assertThat(listingFor(listing.estateId()).priceTiers().getFirst().paymentPlans()).isEmpty();
        assertThat(query("SELECT count(*) FROM estate_payment_plans WHERE estate_id = '" + listing.estateId()
                + "' AND version = 1")).as("the old version is kept").isEqualTo("1");
    }

    @Test
    void aPlansTermsAreChecked() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, TOP_RANK_LAND);

        assertThat(declarePlans(developer, listing.estateId(), """
                [{"name":"A","depositPct":30,"depositAmount":1000000,"instalmentCount":12,"frequency":"monthly"}]
                """).getBody()).contains("either a percentage or a fixed amount");
        assertThat(declarePlans(developer, listing.estateId(), """
                [{"name":"A","depositPct":30,"instalmentCount":12,"frequency":"monthly"},
                 {"name":"a","depositPct":20,"instalmentCount":6,"frequency":"monthly"}]
                """).getBody()).contains("Two plans are called");
        assertThat(declarePlans(developer, listing.estateId(), """
                [{"name":"A","depositPct":30,"instalmentCount":12,"frequency":"weekly"}]
                """).getBody()).contains("monthly' or 'quarterly");
        assertThat(declarePlans(developer, listing.estateId(), """
                [{"name":"A","depositPct":30,"instalmentCount":61,"frequency":"monthly"}]
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(query("SELECT count(*) FROM estate_payment_plans WHERE estate_id = '" + listing.estateId() + "'"))
                .isEqualTo("0");
    }

    /**
     * Flexible contribution, phase 1: a flexible plan sits beside an instalment
     * plan, every figure worked out by HomeTrue in naira — what leaving returns
     * and the buyer's share of any gain. Penalties are still priced on the
     * instalment plan, even when the flexible one is listed first.
     */
    @Test
    void aFlexiblePlanIsDeclaredAndPublishedInNairaBesideAnInstalmentPlan() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, TOP_RANK_LAND);
        declareFees(developer, listing.estateId(), topRankFees());
        declareTerms(developer, listing.estateId());
        assertThat(publish(developer, listing.estateId()).getStatusCode()).isEqualTo(HttpStatus.OK);

        String both = "[" + FLEXIBLE_PLAN + "," + TWELVE_MONTH_PLAN.strip().substring(1);
        ResponseEntity<String> declared = declarePlans(developer, listing.estateId(), both);
        assertThat(declared.getStatusCode()).as(declared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(declared.getBody()).contains("\"kind\":\"flexible\"", "\"kind\":\"installment\"",
                "\"developerGainSharePct\":40", "\"exitAppliesTo\":\"amount_paid\"", "\"minimumToStart\":990000");
        assertThat(query("SELECT string_agg(kind || '/' || coalesce(instalment_count::text, '-'), ',' ORDER BY "
                + "sort_order) FROM estate_payment_plans WHERE estate_id = '" + listing.estateId() + "'"))
                .isEqualTo("FLEXIBLE/-,INSTALMENT/12");

        var tier = listingFor(listing.estateId()).priceTiers().getFirst();
        var flexible = tier.paymentPlans().getFirst();
        assertThat(flexible.kind()).isEqualTo("flexible");
        assertThat(flexible.frequency()).isNull();
        assertThat(flexible.instalmentCount()).isNull();
        assertThat(flexible.ifAnInstalmentIsLate()).as("nothing to be late for").isEmpty();
        assertThat(flexible.planTotal()).isEqualByComparingTo("4950000");
        assertThat(flexible.deposit()).as("the minimum to start").isEqualByComparingTo("990000");
        assertThat(flexible.flexible().minimumContribution()).isEqualByComparingTo("100000");
        assertThat(flexible.flexible().ifYouStopAfterPaying()).satisfiesExactly(
                start -> {
                    assertThat(start.paid()).isEqualByComparingTo("990000");
                    assertThat(start.youGetBack()).isEqualByComparingTo("841500");
                },
                half -> {
                    assertThat(half.paid()).isEqualByComparingTo("2475000");
                    assertThat(half.youGetBack()).isEqualByComparingTo("2103750");
                    assertThat(half.developerKeeps()).isEqualByComparingTo("371250");
                });
        assertThat(flexible.flexible().ifSoldForMore().buyerSharePct()).isEqualByComparingTo("60");
        assertThat(flexible.flexible().ifSoldForMore().perMillionAbove()).isEqualByComparingTo("600000");
        assertThat(flexible.flexible().inactivityMonths()).isEqualTo(6);
        assertThat(flexible.flexible().maxMonths()).isEqualTo(36);
        assertThat(tier.paymentPlans().getLast().kind()).isEqualTo("installment");
        assertThat(listingFor(listing.estateId()).costDisclosure().exitCosts().penaltyBasisPlan())
                .as("penalties are priced on the instalment plan").isEqualTo("12-month plan");
        assertThat(query("SELECT detail FROM audit_log_entries WHERE action = 'estate.payment_plans_declared' "
                + "AND target_id = '" + listing.estateId() + "'")).contains("flexible, 20% to start",
                "developer keeps 40% of any gain", "finish within 36 months");
    }

    /** Every flexible term is checked, and the gain share has no default (FC-8). */
    @Test
    void aFlexiblePlansTermsAreChecked() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, TOP_RANK_LAND);
        String ok = FLEXIBLE_PLAN.strip();

        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"depositPct\":20", "\"depositPct\":5")
                + "]").getBody()).contains("at least 10% of the price");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"developerGainSharePct\":40,", "")
                + "]").getBody()).as("no default gain share").contains("VALIDATION_ERROR", "developerGainSharePct");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"inactivityMonths\":6",
                "\"inactivityMonths\":5") + "]").getBody()).contains("inactivityMonths");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"noticeDays\":30",
                "\"noticeDays\":29") + "]").getBody()).contains("noticeDays");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"maxMonths\":36",
                "\"maxMonths\":11") + "]").getBody()).contains("maxMonths");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"premiumPct\":10,",
                "\"premiumPct\":10,\"instalmentCount\":12,") + "]").getBody()).contains("no fixed deposit, instalments");
        assertThat(declarePlans(developer, listing.estateId(), """
                [{"name":"F","kind":"flexible","depositPct":20}]
                """).getBody()).contains("declare its terms in 'flexible'");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"amount_paid\"", "\"sale_price\"")
                + "]").getBody()).contains("'amount_paid' or 'total_price'");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"kind\":\"flexible\"",
                "\"kind\":\"installment\"") + "]").getBody()).contains("needs an instalmentCount");
        assertThat(declarePlans(developer, listing.estateId(), "[" + ok.replace("\"kind\":\"flexible\"",
                "\"kind\":\"weekly\"") + "]").getBody()).contains("'installment' or 'flexible'");
        assertThat(query("SELECT count(*) FROM estate_payment_plans WHERE estate_id = '" + listing.estateId() + "'"))
                .isEqualTo("0");
    }

    @Test
    void anotherDevelopersPlansAreNotReadable() {
        Developer owner = verifiedDeveloper();
        Listing listing = publishedListingWith(owner, TOP_RANK_LAND, topRankFees(), TWELVE_MONTH_PLAN);

        ResponseEntity<String> other = restTemplate.exchange(
                "/api/portal/estates/" + listing.estateId() + "/payment-plans", HttpMethod.GET,
                new HttpEntity<>(bearer(verifiedDeveloper().token())), String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Neither clause is hidden on its own. Read the penalty schedule and the
     * refund policy separately and a buyer can still miss that there is no
     * affordable way out at all.
     */
    @Test
    void bothExitsAreShownTogetherSoTheAbsenceOfACheapOneIsVisible() {
        Listing listing = publishedListingWith(TOP_RANK_LAND, topRankFees(), TWELVE_MONTH_PLAN);

        var exit = listingFor(listing.estateId()).costDisclosure().exitCosts();

        assertThat(exit.bothPathsCarryACost())
                .as("fall behind and you are penalised; withdraw and you forfeit — there is no free exit")
                .isTrue();
        assertThat(exit.ifYouFallBehind()).isNotEmpty();
        assertThat(exit.ifYouWithdraw().totalLoss().signum()).isPositive();
        assertThat(exit.revocation().afterMonthsUnpaid())
                .as("when the land can be taken back is a number the buyer reads before paying (phase 5b)")
                .isEqualTo(12);
        assertThat(exit.revocation().refundedAsOnWithdrawal())
                .as("and what happens to money already paid is stated: what a withdrawal returns")
                .isTrue();
        assertThat(exit.basisLandPrice())
                .as("the figures name the tier they were computed against")
                .isEqualByComparingTo(TOP_RANK_LAND);
    }

    // ------------------------------------------------------------------
    // Publication, isolation, versioning
    // ------------------------------------------------------------------

    @Test
    void theFullScheduleIsReadableWithNoAuthenticationAtAll() {
        Listing listing = publishedListingWith(DOUBLE_KING_LAND, doubleKingFees());

        ResponseEntity<String> anonymous = restTemplate.getForEntity(
                "/api/marketplace/estates/" + listing.estateId(), String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymous.getBody())
                .as("a buyer must be able to compare true costs before deciding to register at all")
                .contains("infrastructure")
                .contains("10010000");
    }

    @Test
    void anotherDevelopersFeeScheduleIsNotReadable() {
        Developer mine = verifiedDeveloper();
        Listing listing = estateWithATier(mine, DOUBLE_KING_LAND);
        declareFees(mine, listing.estateId(), doubleKingFees());

        Developer rival = verifiedDeveloper();

        assertThat(restTemplate.exchange(
                "/api/portal/estates/" + listing.estateId() + "/fees", HttpMethod.GET,
                new HttpEntity<>(bearer(rival.token())), String.class).getStatusCode())
                .as("a fee schedule is a commercial position, and the database is what keeps it private")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void revisingAScheduleWritesANewVersionAndLeavesTheOldOneIntact() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, DOUBLE_KING_LAND);

        FeeScheduleDto first = declareFees(developer, listing.estateId(), doubleKingFees());
        FeeScheduleDto second = declareFees(developer, listing.estateId(), """
                [{"feeType":"application","amount":25000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"at_application","refundable":false,"isMandatory":true}]
                """);

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.fees()).hasSize(1);
        assertThat(countFeeRows(listing.estateId()))
                .as("the version a buyer was shown must still be there to point at")
                .isEqualTo(doubleKingFeeCount() + 1);
    }

    // ------------------------------------------------------------------
    // Eligibility as a portal read
    // ------------------------------------------------------------------

    /**
     * The point of exposing these: a developer learns what is outstanding
     * from a read, not by attempting a publish and catching one failure at
     * a time. Nothing here is inferred — an unknown condition would have to
     * be reported as unknown, never as met.
     */
    @Test
    void theEstateDetailNamesEveryOutstandingConditionWithoutAttemptingAPublish() {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, TOP_RANK_LAND);

        EstateEligibilityDto before = estateDetail(developer, listing.estateId()).eligibility();

        assertThat(before.tenantVerified()).isTrue();
        assertThat(before.tenantEntitled()).isTrue();
        assertThat(before.tenantActive()).isTrue();
        assertThat(before.noBlockingConflict())
                .as("explicit, not inferred from 'everything else passes but eligible is false'")
                .isTrue();
        assertThat(before.feesDeclared()).isFalse();
        assertThat(before.refundTermsDeclared()).isFalse();
        assertThat(before.published()).isFalse();
        assertThat(before.eligible()).isFalse();

        declareFees(developer, listing.estateId(), topRankFees());
        declareTerms(developer, listing.estateId());
        assertThat(publish(developer, listing.estateId()).getStatusCode()).isEqualTo(HttpStatus.OK);

        EstateEligibilityDto after = estateDetail(developer, listing.estateId()).eligibility();
        assertThat(after.feesDeclared()).isTrue();
        assertThat(after.refundTermsDeclared()).isTrue();
        assertThat(after.published()).isTrue();
        assertThat(after.eligible()).isTrue();
    }

    private EstateDetailDto estateDetail(Developer developer, UUID estateId) {
        ResponseEntity<EstateDetailDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId, HttpMethod.GET,
                new HttpEntity<>(bearer(developer.token())), EstateDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static String doubleKingFees() {
        return """
                [{"feeType":"application","amount":10000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"at_application","refundable":false,"isMandatory":true},
                 {"feeType":"setting_out","amount":300000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"on_construction_start","refundable":false,"isMandatory":true,
                  "notes":"Charged on construction, which the terms require."},
                 {"feeType":"infrastructure","amount":3500000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"on_construction_start","refundable":false,"isMandatory":true},
                 {"feeType":"construction_supervision","amount":200000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"on_construction_start","refundable":false,"isMandatory":true},
                 {"feeType":"facility_management","amount":150000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"annual","refundable":false,"isMandatory":true,
                  "notes":"Payable by 15 January each year."}]
                """;
    }

    private static int doubleKingFeeCount() {
        return 5;
    }

    private static String topRankFees() {
        return """
                [{"feeType":"application","amount":10000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"at_application","refundable":false,"isMandatory":true},
                 {"feeType":"setting_out","amount":500000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"on_construction_start","refundable":false,"isMandatory":true},
                 {"feeType":"infrastructure","amount":7000000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"on_construction_start","refundable":false,"isMandatory":true},
                 {"feeType":"construction_supervision","amount":600000.0000,"currency":"NGN","isFixed":true,
                  "dueTrigger":"on_construction_start","refundable":false,"isMandatory":true}]
                """;
    }

    private Listing publishedListingWith(BigDecimal landPrice, String feesJson) {
        Developer developer = verifiedDeveloper();
        Listing listing = estateWithATier(developer, landPrice);
        declareFees(developer, listing.estateId(), feesJson);
        declareTerms(developer, listing.estateId());
        assertThat(publish(developer, listing.estateId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        return listing;
    }

    private Listing publishedListingWith(BigDecimal landPrice, String feesJson, String plansJson) {
        return publishedListingWith(verifiedDeveloper(), landPrice, feesJson, plansJson);
    }

    private Listing publishedListingWith(Developer developer, BigDecimal landPrice, String feesJson,
                                         String plansJson) {
        Listing listing = estateWithATier(developer, landPrice);
        declareFees(developer, listing.estateId(), feesJson);
        declareTerms(developer, listing.estateId());
        assertThat(publish(developer, listing.estateId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> plans = declarePlans(developer, listing.estateId(), plansJson);
        assertThat(plans.getStatusCode()).as(plans.getBody()).isEqualTo(HttpStatus.OK);
        return listing;
    }

    private ResponseEntity<String> declarePlans(Developer developer, UUID estateId, String plansJson) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/payment-plans", HttpMethod.PUT,
                entity(developer.token(), "{\"plans\":" + plansJson + "}"), String.class);
    }

    private FeeScheduleDto declareFees(Developer developer, UUID estateId, String feesJson) {
        ResponseEntity<FeeScheduleDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/fees", HttpMethod.PUT,
                entity(developer.token(), "{\"fees\":" + feesJson + "}"), FeeScheduleDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** Top Rank's real refund and default terms: 20%, 90 days, 5/10/20% escalation. */
    private void declareTerms(Developer developer, UUID estateId) {
        ResponseEntity<String> refund = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/refund-terms", HttpMethod.PUT,
                entity(developer.token(), """
                        {"deductionPct":20.00,"processingDays":90,"appliesTo":"total_price",
                         "nonRefundableFeeTypes":["application"]}
                        """), String.class);
        assertThat(refund.getStatusCode()).as(refund.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> defaults = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/default-terms", HttpMethod.PUT,
                entity(developer.token(), """
                        {"revocationAfterMonths":12,
                         "revocationNoticeDays":30,
                         "developmentDeadlineMonths":3,
                         "transferRequiresConsent":true,
                         "penaltyTiers":[{"monthsLate":3,"penaltyPct":5.00},
                                         {"monthsLate":6,"penaltyPct":10.00},
                                         {"monthsLate":12,"penaltyPct":20.00}],
                         "penaltiesRefundable":false}
                        """), String.class);
        assertThat(defaults.getStatusCode()).as(defaults.getBody()).isEqualTo(HttpStatus.OK);
    }

    private Listing estateWithATier(Developer developer, BigDecimal landPrice) {
        UUID branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + developer.tenantId() + "', 'Head Office')");

        int band = BAND.incrementAndGet();
        String name = "Disclosure Gardens " + band;
        EstateDto estate = restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(developer.token(), new CreateEstateRequest(
                        name, null, "Gwarinpa", "FCT", "Abuja", "1 Test Close",
                        null, null, List.of("Perimeter fence"), branchId,
                        new GeoJsonPolygonDto("Polygon", List.of(ring(band))))),
                EstateDto.class).getBody();

        UUID tierId = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/price-tiers", HttpMethod.POST,
                entity(developer.token(), new CreatePriceTierRequest(
                        "LAND_SIZE", new BigDecimal("250.00"), landPrice, Currency.NGN, "Standard 250")),
                PriceTierDto.class).getBody().id();

        UUID blockId = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/blocks", HttpMethod.POST,
                entity(developer.token(), new CreateBlockRequest("A", "Block A")), BlockDto.class)
                .getBody().id();

        ResponseEntity<String> plots = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/plots", HttpMethod.POST,
                entity(developer.token(), new CreatePlotsRequest(List.of(
                        new CreatePlotRequest("001", blockId, tierId, false, "available-dev",
                                null, null, null, null, null, null)))),
                String.class);
        assertThat(plots.getStatusCode()).as(plots.getBody()).isEqualTo(HttpStatus.CREATED);

        return new Listing(estate.id(), name);
    }

    private ResponseEntity<String> publish(Developer developer, UUID estateId) {
        execute(EstatePhotoFixture.coverPhotoSql(estateId)); // changeset 086: listable needs a photo
        execute(LetterApprovalFixture.approvedLetterSql(estateId)); // 095: and an approved letter
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/publish", HttpMethod.POST,
                entity(developer.token(), ""), String.class);
    }

    private List<String> feedNames() {
        return restTemplate.exchange("/api/marketplace/estates?limit=100", HttpMethod.GET,
                HttpEntity.EMPTY, new ParameterizedTypeReference<PageResponse<MarketplaceListingDto>>() {
                }).getBody().items().stream().map(MarketplaceListingDto::name).toList();
    }

    private MarketplaceListingDto listingFor(UUID estateId) {
        ResponseEntity<MarketplaceListingDto> response = restTemplate.getForEntity(
                "/api/marketplace/estates/" + estateId, MarketplaceListingDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Developer verifiedDeveloper() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Disclosure Co " + rc, null, rc, "Limited Liability (Ltd)",
                        "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");

        ResponseEntity<TenantDetailDto> created = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(login(ADMIN_EMAIL), request),
                TenantDetailDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = created.getBody().id();

        execute("UPDATE organizations SET verification_state = 'VERIFIED', marketplace_publishing = true, "
                + "status = 'ACTIVE' WHERE id = '" + tenantId + "'");

        String email = "ed+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN),
                AuthResponse.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + tenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('"
                + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, NULL FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = 'executive_director'");

        return new Developer(tenantId, login(email));
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private static List<List<BigDecimal>> ring(int band) {
        double lng = 7.30 + band * 0.05;
        double lat = 9.05;
        return Arrays.stream(new double[][] {
                        {lng, lat}, {lng + 0.01, lat}, {lng + 0.01, lat + 0.01}, {lng, lat + 0.01}, {lng, lat}})
                .map(p -> List.of(BigDecimal.valueOf(p[0]), BigDecimal.valueOf(p[1])))
                .toList();
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

    private String estateName(UUID estateId) {
        return query("SELECT name FROM estates WHERE id = '" + estateId + "'");
    }

    private int countFeeRows(UUID estateId) {
        return Integer.parseInt(query("SELECT count(*) FROM estate_fees WHERE estate_id = '" + estateId + "'"));
    }

    private static String query(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
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

    private record Developer(UUID tenantId, String token) {
    }

    private record Listing(UUID estateId, String name) {
    }
}
