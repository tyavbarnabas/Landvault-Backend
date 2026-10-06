package com.techcomfort.landvaultbackend.conflicts;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a boundary change did to an estate's conflicts, itemised — so the
 * developer sees <em>which</em> overlaps cleared or appeared, not just a
 * count. Built from two snapshots of the owning company's own view, so it
 * never names the other party.
 *
 * @param raised         live now, and weren't before — a new overlap.
 * @param resolved       live before, closed now — cleared on their own.
 * @param awaitingReview the overlap is gone but a person must close it
 *                       first (a cross-company conflict, CD-9): still blocking.
 * @param stillOpen      live before and after, unchanged in kind.
 */
public record ConflictChanges(
        List<ConflictItem> raised,
        List<ConflictItem> resolved,
        List<ConflictItem> awaitingReview,
        List<ConflictItem> stillOpen
) {

    public static ConflictChanges between(List<ConflictItem> before, List<ConflictItem> after) {
        Map<UUID, ConflictItem> was = before.stream().collect(Collectors.toMap(ConflictItem::id, Function.identity()));
        Map<UUID, ConflictItem> now = after.stream().collect(Collectors.toMap(ConflictItem::id, Function.identity()));

        List<ConflictItem> raised = after.stream()
                .filter(ConflictItem::live)
                .filter(c -> !was.containsKey(c.id()) || !was.get(c.id()).live())
                .toList();
        List<ConflictItem> resolved = before.stream()
                .filter(ConflictItem::live)
                .filter(c -> now.containsKey(c.id()) && !now.get(c.id()).live())
                .map(c -> now.get(c.id()))
                .toList();
        List<ConflictItem> awaitingReview = after.stream()
                .filter(c -> c.live() && c.underReview())
                .filter(c -> was.containsKey(c.id()) && was.get(c.id()).live() && !was.get(c.id()).underReview())
                .toList();
        List<ConflictItem> stillOpen = after.stream()
                .filter(ConflictItem::live)
                .filter(c -> was.containsKey(c.id()) && was.get(c.id()).live())
                .filter(c -> !awaitingReview.contains(c))
                .toList();
        return new ConflictChanges(raised, resolved, awaitingReview, stillOpen);
    }
}
