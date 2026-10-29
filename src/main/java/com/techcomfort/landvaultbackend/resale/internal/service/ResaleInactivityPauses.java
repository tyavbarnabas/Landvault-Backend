package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.payments.InactivityPauses;
import com.techcomfort.landvaultbackend.resale.internal.domain.ResaleListing;
import com.techcomfort.landvaultbackend.resale.internal.repository.ResaleListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The payments module's {@link InactivityPauses}: when a flexible plan's first
 * listing stopped its inactivity clock (D24) — from listing until it came
 * down, never past its 90 days.
 */
@Service
@RequiredArgsConstructor
public class ResaleInactivityPauses implements InactivityPauses {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final ResaleListingRepository listings;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<Pause>> pausesOf(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        return listings.findByTransactionIdInAndPausesInactivityTrue(transactionIds).stream()
                .sorted(Comparator.comparing(ResaleListing::getListedAt))
                .collect(Collectors.groupingBy(ResaleListing::getTransactionId,
                        Collectors.mapping(ResaleInactivityPauses::pause, Collectors.toList())));
    }

    private static Pause pause(ResaleListing l) {
        Instant end = l.getClosedAt() == null || l.getClosedAt().isAfter(l.getExpiresAt())
                ? l.getExpiresAt() : l.getClosedAt();
        LocalDate until = l.getClosedAt() == null && end.isAfter(Instant.now()) ? null : LocalDate.ofInstant(end, LAGOS);
        return new Pause(LocalDate.ofInstant(l.getListedAt(), LAGOS), until);
    }
}
