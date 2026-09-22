package com.techcomfort.landvaultbackend.conflicts;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.conflicts.dto.ConflictStatusRequest;
import com.techcomfort.landvaultbackend.conflicts.internal.domain.ListingConflict;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictSeverity;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictStatus;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictType;
import com.techcomfort.landvaultbackend.conflicts.internal.exceptions.ConflictException;
import com.techcomfort.landvaultbackend.conflicts.internal.repository.ListingConflictRepository;
import com.techcomfort.landvaultbackend.conflicts.internal.service.ConflictReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The review state machine (CD-7), away from a database.
 * <p>
 * Note what is deliberately <em>not</em> here: pair ordering and severity.
 * Both are computed inside the detection SQL (changeset 047) rather than in
 * Java, because detection has to be one set-based statement that reads
 * across tenants — so they are exercised in
 * {@code ListingConflictDetectionIT} against real PostGIS instead. Pair
 * ordering additionally has a database CHECK constraint behind it, which no
 * Java test could stand in for.
 */
@ExtendWith(MockitoExtension.class)
class ConflictReviewRulesTest {

    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private final UUID conflictId = UUID.randomUUID();

    @Mock
    private ListingConflictRepository repository;

    @Mock
    private AuditApi auditApi;

    @InjectMocks
    private ConflictReviewService service;

    @BeforeEach
    void establishScope() {
        TenantContext.set(new TenantScope(REVIEWER, null, null, true));
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /**
     * The one transition the system reserves for itself. A reviewer marking
     * a conflict "resolved itself" while the land still overlaps would be
     * asserting a fact about the world they are not in a position to
     * assert.
     */
    @Test
    void autoResolvedCannotBeSetByHand() {
        givenConflict(ConflictStatus.OPEN, TENANT_A, TENANT_B);

        assertThatThrownBy(() -> service.changeStatus(
                conflictId(), new ConflictStatusRequest("auto_resolved", "Looks fine now.")))
                .isInstanceOf(ConflictException.InvalidTransition.class)
                .hasMessageContaining("set by the system");

        verify(repository, never()).save(any());
        verify(auditApi, never()).record(any());
    }

    @Test
    void aConflictCannotBeMovedBackToOpen() {
        givenConflict(ConflictStatus.INVESTIGATING, TENANT_A, TENANT_B);

        assertThatThrownBy(() -> service.changeStatus(
                conflictId(), new ConflictStatusRequest("open", null)))
                .isInstanceOf(ConflictException.InvalidTransition.class);
    }

    /** A decision blocks or unblocks a company's listing, so it is justified. */
    @Test
    void aDecisionWithoutAReasonIsRejected() {
        givenConflict(ConflictStatus.INVESTIGATING, TENANT_A, TENANT_B);

        assertThatThrownBy(() -> service.changeStatus(
                conflictId(), new ConflictStatusRequest("confirmed_duplicate", "   ")))
                .isInstanceOf(ConflictException.InvalidTransition.class)
                .hasMessageContaining("reason is required");

        verify(repository, never()).save(any());
    }

    /**
     * Picking the work up is not a decision. Demanding a justification for
     * it trains reviewers to type something meaningless, which makes the
     * justifications that do matter less trustworthy.
     */
    @Test
    void movingToInvestigatingNeedsNoReason() {
        ListingConflict conflict = givenConflict(ConflictStatus.OPEN, TENANT_A, TENANT_B);

        service.changeStatus(conflictId(), new ConflictStatusRequest("investigating", null));

        assertThat(conflict.getStatus()).isEqualTo(ConflictStatus.INVESTIGATING);
        assertThat(conflict.getResolvedAt())
                .as("investigating is in-progress; a resolved timestamp would read as closed")
                .isNull();
    }

    @Test
    void aClosedConflictCannotBeReDecided() {
        givenConflict(ConflictStatus.DISMISSED, TENANT_A, TENANT_B);

        assertThatThrownBy(() -> service.changeStatus(
                conflictId(), new ConflictStatusRequest("confirmed_duplicate", "Changed my mind.")))
                .isInstanceOf(ConflictException.InvalidTransition.class)
                .hasMessageContaining("already closed");
    }

    /**
     * The one deliberate exception to "a decision is terminal": geometry
     * clearing must never itself release a confirmed duplicate (that is
     * the whole reason {@code geometryClearedAt} exists — see AGENTS.md),
     * so a human still needs a way to stand down once they have actually
     * reviewed a correction and agree it resolves things.
     */
    @Test
    void aConfirmedDuplicateCanBeDismissedAfterReview() {
        ListingConflict conflict = givenConflict(ConflictStatus.CONFIRMED_DUPLICATE, TENANT_A, TENANT_B);

        service.changeStatus(conflictId(), new ConflictStatusRequest(
                "dismissed", "Reviewed the corrected survey — boundaries no longer conflict."));

        assertThat(conflict.getStatus()).isEqualTo(ConflictStatus.DISMISSED);
    }

    /**
     * But that is the only exit. Re-opening it would imply there is
     * something left to investigate, when the only real question is
     * whether the existing decision still stands.
     */
    @Test
    void aConfirmedDuplicateCannotBeSentBackToInvestigating() {
        givenConflict(ConflictStatus.CONFIRMED_DUPLICATE, TENANT_A, TENANT_B);

        assertThatThrownBy(() -> service.changeStatus(
                conflictId(), new ConflictStatusRequest("investigating", null)))
                .isInstanceOf(ConflictException.InvalidTransition.class)
                .hasMessageContaining("can only be moved to dismissed");
    }

    /** Usually means the reviewer is acting on a stale view. */
    @Test
    void settingTheSameStatusIsRejected() {
        givenConflict(ConflictStatus.INVESTIGATING, TENANT_A, TENANT_B);

        assertThatThrownBy(() -> service.changeStatus(
                conflictId(), new ConflictStatusRequest("investigating", null)))
                .isInstanceOf(ConflictException.InvalidTransition.class)
                .hasMessageContaining("already investigating");
    }

    /**
     * A conflict spans two companies, so a decision belongs on both
     * records — one entry each, never one company's record carrying the
     * whole story.
     */
    @Test
    void aCrossTenantDecisionIsAuditedAgainstBothCompanies() {
        givenConflict(ConflictStatus.INVESTIGATING, TENANT_A, TENANT_B);

        service.changeStatus(conflictId(),
                new ConflictStatusRequest("confirmed_duplicate", "Same survey plan filed twice."));

        ArgumentCaptor<AuditEntryRequest> captor = ArgumentCaptor.forClass(AuditEntryRequest.class);
        verify(auditApi, times(2)).record(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(AuditEntryRequest::tenantId)
                .containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        assertThat(captor.getAllValues()).allSatisfy(entry -> {
            assertThat(entry.action()).isEqualTo("listing_conflict.confirmed_duplicate");
            assertThat(entry.actorUserId())
                    .as("the decider comes from the authenticated scope, never the request body")
                    .isEqualTo(REVIEWER);
            assertThat(entry.privileged())
                    .as("ordinary admin review, not a support-access grant")
                    .isFalse();
        });
    }

    /** Otherwise one company's record carries the same decision twice. */
    @Test
    void aSameTenantDecisionIsAuditedOnce() {
        givenConflict(ConflictStatus.OPEN, TENANT_A, TENANT_A);

        service.changeStatus(conflictId(),
                new ConflictStatusRequest("dismissed", "Both boundaries are correct; they abut."));

        verify(auditApi, times(1)).record(any());
    }

    /** The predicate in changeset 046's partial unique index, mirrored in Java. */
    @Test
    void liveStatusesMatchTheUniqueIndexPredicate() {
        assertThat(List.of(ConflictStatus.OPEN, ConflictStatus.INVESTIGATING,
                ConflictStatus.CONFIRMED_DUPLICATE))
                .allMatch(ConflictStatus::isLive);
        assertThat(List.of(ConflictStatus.DISMISSED, ConflictStatus.AUTO_RESOLVED))
                .noneMatch(ConflictStatus::isLive);
    }

    // --- fixture ---

    private UUID conflictId() {
        return conflictId;
    }

    private ListingConflict givenConflict(ConflictStatus status, UUID leftTenant, UUID rightTenant) {
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        ListingConflict conflict = ListingConflict.builder()
                .conflictType(ConflictType.ESTATE_OVERLAP)
                .leftEntityId(left.compareTo(right) < 0 ? left : right)
                .rightEntityId(left.compareTo(right) < 0 ? right : left)
                .leftTenantId(leftTenant)
                .rightTenantId(rightTenant)
                .overlapAreaSqm(new BigDecimal("194480.00"))
                .leftOverlapPct(new BigDecimal("19.75"))
                .rightOverlapPct(new BigDecimal("19.75"))
                .severity(leftTenant.equals(rightTenant) ? ConflictSeverity.MEDIUM : ConflictSeverity.HIGH)
                .status(status)
                .detectedAt(Instant.now())
                .build();
        conflict.setId(conflictId);
        lenient().when(repository.findById(conflictId)).thenReturn(Optional.of(conflict));
        lenient().when(repository.save(any(ListingConflict.class)))
                .thenAnswer(call -> call.getArgument(0));
        return conflict;
    }
}
