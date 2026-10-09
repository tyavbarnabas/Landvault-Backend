package com.techcomfort.landvaultbackend.checkout.internal.repository;

import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByReservationId(UUID reservationId);

    boolean existsByReservationId(UUID reservationId);

    boolean existsByPlotId(UUID plotId);

    Optional<Transaction> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    /**
     * A confirmed payment moves a transaction on — once. One statement that
     * only matches a transaction still waiting for payment, so a second
     * confirmation (callback and webhook both firing) updates nothing.
     * Returns how many rows moved: 1 the first time, 0 after.
     */
    @Modifying
    @Query(value = "UPDATE transactions SET status = 'AWAITING_FINANCE', updated_at = now() "
            + "WHERE id = :id AND status = 'PENDING_PAYMENT'", nativeQuery = true)
    int moveToAwaitingFinance(@Param("id") UUID id);

    /** Purchases still waiting for payment whose hold ran out before the cutoff. */
    @Query(value = "SELECT t.id FROM transactions t JOIN reservations r ON r.id = t.reservation_id "
            + "WHERE t.status = 'PENDING_PAYMENT' AND r.expires_at < :cutoff ORDER BY r.expires_at", nativeQuery = true)
    List<UUID> findPendingWithHoldExpiredBefore(@Param("cutoff") Instant cutoff);

    /** Same once-only shape as {@link #moveToAwaitingFinance}: 1 the first time, 0 after. */
    @Modifying
    @Query(value = "UPDATE transactions SET status = 'ABANDONED', updated_at = now() "
            + "WHERE id = :id AND status = 'PENDING_PAYMENT'", nativeQuery = true)
    int moveToAbandoned(@Param("id") UUID id);

    /**
     * FV-2's queue for one company. The joins to plots, estates and blocks run
     * under the caller's own row-level security, so a branch-scoped finance
     * officer sees only their branch's sales without any branch condition here.
     * Rows: transaction id, reference, estate id, estate name, plot id, block
     * name, plot number, buyer id, total price, currency, awaiting since.
     */
    @Query(value = "SELECT t.id, t.reference, e.id AS estate_id, e.name, p.id AS plot_id, b.name AS block_name, "
            + "p.plot_number, t.buyer_user_id, t.total_price, t.currency, t.updated_at "
            + "FROM transactions t JOIN plots p ON p.id = t.plot_id JOIN estates e ON e.id = t.estate_id "
            + "LEFT JOIN blocks b ON b.id = p.block_id "
            + "WHERE t.status = 'AWAITING_FINANCE' AND t.seller_tenant_id = :tenantId "
            + "ORDER BY t.updated_at", nativeQuery = true)
    List<Object[]> findAwaitingFinance(@Param("tenantId") UUID tenantId);

    @Modifying
    @Query(value = "UPDATE transactions SET status = :to, updated_at = now() "
            + "WHERE id = :id AND status = 'AWAITING_FINANCE'", nativeQuery = true)
    int moveFromAwaitingFinance(@Param("id") UUID id, @Param("to") String to);

    /**
     * Verified sales, for payouts. Rows: transaction id, reference, seller
     * tenant id, estate name, block name, plot number, total price, currency,
     * verified at (the last status change). {@code :id} null means all.
     */
    @Query(value = "SELECT t.id, t.reference, t.seller_tenant_id, e.name, b.name AS block_name, p.plot_number, "
            + "t.total_price, t.currency, t.updated_at "
            + "FROM transactions t JOIN plots p ON p.id = t.plot_id JOIN estates e ON e.id = t.estate_id "
            + "LEFT JOIN blocks b ON b.id = p.block_id "
            + "WHERE t.status = 'VERIFIED' AND (CAST(:id AS uuid) IS NULL OR t.id = :id) "
            + "ORDER BY t.updated_at", nativeQuery = true)
    List<Object[]> findVerifiedSales(@Param("id") UUID id);

    /**
     * Late money. Rows: transaction id, reference, estate name, block name,
     * plot number, total price, currency, plot status — joined under the
     * caller's row-level security.
     */
    @Query(value = "SELECT t.id, t.reference, e.name, b.name AS block_name, p.plot_number, t.total_price, t.currency, "
            + "p.status FROM transactions t JOIN plots p ON p.id = t.plot_id JOIN estates e ON e.id = t.estate_id "
            + "LEFT JOIN blocks b ON b.id = p.block_id "
            + "WHERE t.status = 'ABANDONED' AND t.seller_tenant_id = :tenantId AND t.id IN (:ids) "
            + "ORDER BY t.updated_at", nativeQuery = true)
    List<Object[]> findAbandonedSales(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /** Once only, like the other moves: 1 the first time, 0 after. */
    @Modifying
    @Query(value = "UPDATE transactions SET status = :to, updated_at = now() "
            + "WHERE id = :id AND status = 'ABANDONED'", nativeQuery = true)
    int moveFromAbandoned(@Param("id") UUID id, @Param("to") String to);
}
