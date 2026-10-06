package com.techcomfort.landvaultbackend.conflicts;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The four buckets a boundary change can put a conflict in. */
class ConflictChangesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();
    private static final UUID E = UUID.randomUUID();

    @Test
    void eachConflictLandsInExactlyTheRightBucket() {
        List<ConflictItem> before = List.of(
                item(A, "open", true, false),            // will clear on its own
                item(B, "open", true, false),            // cross-company: overlap gone, a human must close it
                item(C, "investigating", true, false),   // untouched
                item(D, "dismissed", false, false));     // closed history, and stays so
        List<ConflictItem> after = List.of(
                item(A, "auto_resolved", false, false),
                item(B, "open", true, true),
                item(C, "investigating", true, false),
                item(D, "dismissed", false, false),
                item(E, "open", true, false));           // new overlap

        ConflictChanges changes = ConflictChanges.between(before, after);

        assertThat(changes.raised()).extracting(ConflictItem::id).containsExactly(E);
        assertThat(changes.resolved()).extracting(ConflictItem::id).containsExactly(A);
        assertThat(changes.resolved().getFirst().status()).as("reported as it is now").isEqualTo("auto_resolved");
        assertThat(changes.awaitingReview()).extracting(ConflictItem::id).containsExactly(B);
        assertThat(changes.stillOpen()).extracting(ConflictItem::id).containsExactly(C);
    }

    @Test
    void nothingBeforeAndNothingAfterIsAllEmpty() {
        ConflictChanges changes = ConflictChanges.between(List.of(), List.of());
        assertThat(changes.raised()).isEmpty();
        assertThat(changes.resolved()).isEmpty();
        assertThat(changes.awaitingReview()).isEmpty();
        assertThat(changes.stillOpen()).isEmpty();
    }

    private static ConflictItem item(UUID id, String status, boolean live, boolean underReview) {
        return new ConflictItem(id, "estate_overlap", UUID.randomUUID(), "Your estate", BigDecimal.TEN, "high",
                status, live, live, underReview, "guidance");
    }
}
