package com.techcomfort.landvaultbackend.checkout.internal.repository;

import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    Page<Transaction> findAllByBuyerUserIdOrderByCreatedAtDesc(UUID buyerUserId, Pageable pageable);

    Optional<Transaction> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    List<Transaction> findByBuyerUserIdAndStatusOrderByCreatedAtDesc(UUID buyerUserId, TransactionStatus status);

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
     * name, plot number, buyer id, total price, currency, awaiting since,
     * the plan's deposit (a flexible plan's minimum to start) and name (null
     * when paying in full), and a flexible plan's total.
     */
    @Query(value = "SELECT t.id, t.reference, e.id AS estate_id, e.name, p.id AS plot_id, b.name AS block_name, "
            + "p.plot_number, t.buyer_user_id, t.total_price, t.currency, t.updated_at, "
            + "COALESCE(t.deposit_amount, t.flexible_minimum_to_start) AS amount_due, t.plan_name, "
            + "CASE WHEN t.plan = 'FLEXIBLE' THEN t.plan_total END AS flexible_plan_total "
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
     * Sales the seller is owed on, for payouts: verified ones, (3c)
     * instalment and flexible purchases on their plan, and (5a/5b) withdrawn or revoked plans — the
     * developer's declared share is still theirs. Rows: transaction id, reference,
     * seller tenant id, estate name, block name, plot number, total price,
     * currency, verified at (the last status change), plan, status, escrow
     * deduction, escrow basis, penalties refundable. {@code :id} null means all.
     */
    @Query(value = "SELECT t.id, t.reference, t.seller_tenant_id, e.name, b.name AS block_name, p.plot_number, "
            + "t.total_price, t.currency, t.updated_at, t.plan, t.status, t.escrow_deduction_pct, t.escrow_applies_to, "
            + "t.penalties_refundable, p.id AS plot_id "
            + "FROM transactions t JOIN plots p ON p.id = t.plot_id JOIN estates e ON e.id = t.estate_id "
            + "LEFT JOIN blocks b ON b.id = p.block_id "
            + "WHERE (t.status = 'VERIFIED' OR (t.status IN ('ON_PLAN', 'WITHDRAWN', 'REVOKED') "
            + "AND t.plan IN ('INSTALLMENT', 'FLEXIBLE'))) "
            + "AND (CAST(:id AS uuid) IS NULL OR t.id = :id) "
            + "ORDER BY t.updated_at", nativeQuery = true)
    List<Object[]> findVerifiedSales(@Param("id") UUID id);

    /**
     * 3b: instalment purchases on their plan — or (5a/5b) withdrawn or revoked, whose
     * last payment may still await finance — for the finance queue: the same
     * columns as {@link #findAwaitingFinance} plus the status, under the
     * caller's RLS.
     */
    @Query(value = "SELECT t.id, t.reference, e.id AS estate_id, e.name, p.id AS plot_id, b.name AS block_name, "
            + "p.plot_number, t.buyer_user_id, t.total_price, t.currency, t.updated_at, t.deposit_amount, "
            + "t.plan_name, t.status, CASE WHEN t.plan = 'FLEXIBLE' THEN t.plan_total END AS flexible_plan_total "
            + "FROM transactions t JOIN plots p ON p.id = t.plot_id JOIN estates e ON e.id = t.estate_id "
            + "LEFT JOIN blocks b ON b.id = p.block_id "
            + "WHERE t.status IN ('ON_PLAN', 'WITHDRAWN', 'REVOKED') AND t.seller_tenant_id = :tenantId AND t.id IN (:ids)", nativeQuery = true)
    List<Object[]> findOnPlan(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /**
     * Phase 4: every running plan of one kind ({@code :plan}, INSTALLMENT or FLEXIBLE) — one company's ({@code :tenantId}) or, with
     * platform scope and a null id, everyone's — read under the caller's RLS,
     * so a branch officer sees only their branch's.
     */
    @Query(value = "SELECT t.id, t.reference, t.seller_tenant_id, e.id AS estate_id, e.name, p.id AS plot_id, "
            + "b.name AS block_name, p.plot_number, t.buyer_user_id, t.plan_name, t.currency, t.updated_at "
            + "FROM transactions t JOIN plots p ON p.id = t.plot_id JOIN estates e ON e.id = t.estate_id "
            + "LEFT JOIN blocks b ON b.id = p.block_id "
            + "WHERE t.status = 'ON_PLAN' AND t.plan = :plan "
            + "AND (CAST(:tenantId AS uuid) IS NULL OR t.seller_tenant_id = :tenantId) "
            + "ORDER BY t.updated_at", nativeQuery = true)
    List<Object[]> findRunningPlans(@Param("tenantId") UUID tenantId, @Param("plan") String plan);

    /** Once only: a plan paid in full becomes a verified sale. */
    @Modifying
    @Query(value = "UPDATE transactions SET status = 'VERIFIED', updated_at = now() "
            + "WHERE id = :id AND status = 'ON_PLAN'", nativeQuery = true)
    int completePlan(@Param("id") UUID id);

    /** Once only: phase 5a, the buyer withdraws from their running plan. */
    @Modifying
    @Query(value = "UPDATE transactions SET status = 'WITHDRAWN', withdrawn_at = now(), withdrawal_reason = :reason, "
            + "updated_at = now() WHERE id = :id AND status = 'ON_PLAN'", nativeQuery = true)
    int withdraw(@Param("id") UUID id, @Param("reason") String reason);

    /** Once only: phase 5c, a restructure puts the next schedule version in force. */
    @Modifying
    @Query(value = "UPDATE transactions SET schedule_version = :from + 1, updated_at = now() "
            + "WHERE id = :id AND status = 'ON_PLAN' AND schedule_version = :from", nativeQuery = true)
    int advanceSchedule(@Param("id") UUID id, @Param("from") int from);

    /** Once only: phase 5b, the developer revokes a running plan. */
    @Modifying
    @Query(value = "UPDATE transactions SET status = 'REVOKED', revoked_at = now(), revocation_reason = :reason, "
            + "updated_at = now() WHERE id = :id AND status = 'ON_PLAN'", nativeQuery = true)
    int revoke(@Param("id") UUID id, @Param("reason") String reason);

    /**
     * Late money. Rows: transaction id, reference, estate name, block name,
     * plot number, total price, currency, plot status, estate id — joined
     * under the caller's row-level security.
     */
    @Query(value = "SELECT t.id, t.reference, e.name, b.name AS block_name, p.plot_number, t.total_price, t.currency, "
            + "p.status, e.id AS estate_id FROM transactions t JOIN plots p ON p.id = t.plot_id JOIN estates e ON e.id = t.estate_id "
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
