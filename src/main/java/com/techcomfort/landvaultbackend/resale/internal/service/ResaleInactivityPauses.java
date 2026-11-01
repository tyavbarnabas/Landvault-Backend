package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.payments.InactivityPauses;
import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleHoldRepository;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payments module's {@link InactivityPauses}: when a flexible plan's first
 * listing stopped its inactivity clock (D24) — from listing until it came
 * down, never past its 90 days — and every new buyer's hold on the plot (D25).
 */
@Service
@RequiredArgsConstructor
public class ResaleInactivityPauses implements InactivityPauses {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final ResaleListingRepository listings;
    private final ResaleHoldRepository holds;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<Pause>> pausesOf(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Pause>> raw = new HashMap<>();
        listings.findByTransactionIdInAndPausesInactivityTrue(transactionIds).forEach(l ->
                raw.computeIfAbsent(l.getTransactionId(), id -> new ArrayList<>()).add(pause(l)));
        // D25: a new buyer holding the plot stops the clock too, on any listing and for as long as it lasts.
        holds.findBySellerTransactionIdIn(transactionIds).forEach(h ->
                raw.computeIfAbsent(h.getSellerTransactionId(), id -> new ArrayList<>()).add(new Pause(
                        LocalDate.ofInstant(h.getHeldAt(), LAGOS),
                        h.getEndedAt() == null ? null : LocalDate.ofInstant(h.getEndedAt(), LAGOS))));
        Map<UUID, List<Pause>> out = new HashMap<>();
        raw.forEach((id, pauses) -> out.put(id, merged(pauses)));
        return out;
    }

    private static Pause pause(ResaleListing l) {
        Instant end = l.getClosedAt() == null || l.getClosedAt().isAfter(l.getExpiresAt())
                ? l.getExpiresAt() : l.getClosedAt();
        LocalDate until = l.getClosedAt() == null && end.isAfter(Instant.now()) ? null : LocalDate.ofInstant(end, LAGOS);
        return new Pause(LocalDate.ofInstant(l.getListedAt(), LAGOS), until);
    }

    /** A hold during the listing's own pause counts once, not twice: overlapping pauses become one. */
    static List<Pause> merged(List<Pause> pauses) {
        List<Pause> sorted = pauses.stream().sorted(Comparator.comparing(Pause::from)).toList();
        List<Pause> out = new ArrayList<>();
        for (Pause p : sorted) {
            if (!out.isEmpty()) {
                Pause last = out.getLast();
                if (last.until() == null || !p.from().isAfter(last.until())) {
                    LocalDate until = last.until() == null || p.until() == null ? null
                            : p.until().isAfter(last.until()) ? p.until() : last.until();
                    out.set(out.size() - 1, new Pause(last.from(), until));
                    continue;
                }
            }
            out.add(p);
        }
        return out;
    }
}
