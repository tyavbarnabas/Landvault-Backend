package com.techcomfort.landvaultbackend.marketplace.internal.service;

import com.techcomfort.landvaultbackend.marketplace.dto.WishlistItemDto;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.ListingView;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.WishlistItem;
import com.techcomfort.landvaultbackend.marketplace.internal.enums.WishlistListingType;
import com.techcomfort.landvaultbackend.marketplace.internal.exceptions.WishlistException;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.ListingViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.WishlistItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A buyer's saved listings. Availability and current figures come from
 * {@code marketplace_listings}, so "not available" never says why.
 * See AGENTS.md, "The buyer's wishlist".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {

    static final int MAX_ITEMS = 200;

    private final WishlistItemRepository items;
    private final ListingViewRepository listings;

    /** {@code created} is false when the listing was already saved. */
    public record Saved(WishlistItemDto item, boolean created) {
    }

    @Transactional(readOnly = true)
    public List<WishlistItemDto> list(UUID buyerUserId) {
        List<WishlistItem> saved = items.findByBuyerUserIdOrderBySavedAtDescIdDesc(buyerUserId);
        Map<UUID, ListingView> live = saved.isEmpty() ? Map.of()
                : listings.findByEstateIdIn(saved.stream().map(WishlistItem::getListingId).toList()).stream()
                        .collect(Collectors.toMap(ListingView::getEstateId, Function.identity()));
        return saved.stream().map(item -> toDto(item, live.get(item.getListingId()))).toList();
    }

    @Transactional
    public Saved save(UUID buyerUserId, String listingType, UUID listingId) {
        WishlistListingType type = WishlistListingType.supported(listingType);
        items.lockBuyer(buyerUserId);

        Optional<ListingView> listing = listings.findById(listingId);
        Optional<WishlistItem> existing = items.findByBuyerUserIdAndListingTypeAndListingId(buyerUserId, type, listingId);
        if (existing.isPresent()) {
            return new Saved(toDto(existing.get(), listing.orElse(null)), false);
        }
        ListingView view = listing.orElseThrow(WishlistException.ListingNotFound::new);
        if (items.countByBuyerUserId(buyerUserId) >= MAX_ITEMS) {
            throw new WishlistException.Full(MAX_ITEMS);
        }
        WishlistItem item = items.save(WishlistItem.builder()
                .buyerUserId(buyerUserId)
                .listingType(type)
                .listingId(listingId)
                .nameAtSave(view.getName())
                .priceAtSave(view.getFromPrice())
                .currency(view.getFromPrice() == null ? null : view.getFromPriceCurrency())
                .savedAt(Instant.now())
                .build());
        log.info("Wishlist item saved: listing {} by buyer {}", listingId, buyerUserId);
        return new Saved(toDto(item, view), true);
    }

    /** Idempotent: removing what isn't saved changes nothing. */
    @Transactional
    public void remove(UUID buyerUserId, String listingType, UUID listingId) {
        WishlistListingType type = WishlistListingType.supported(listingType);
        if (items.deleteSaved(buyerUserId, type, listingId) > 0) {
            log.info("Wishlist item removed: listing {} by buyer {}", listingId, buyerUserId);
        }
    }

    private static WishlistItemDto toDto(WishlistItem item, ListingView live) {
        boolean available = live != null;
        return new WishlistItemDto(
                item.getListingType().value(),
                item.getListingId(),
                available ? live.getName() : item.getNameAtSave(),
                item.getSavedAt(),
                item.getPriceAtSave(),
                item.getCurrency(),
                available,
                available ? live.getFromPrice() : null,
                available && live.getFromPrice() != null ? live.getFromPriceCurrency() : null,
                available ? live.getPlotsRemaining() : null);
    }
}
