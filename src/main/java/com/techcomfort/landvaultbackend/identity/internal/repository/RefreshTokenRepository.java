package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Looked up by hash, never by raw value — see AGENTS.md.
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // The token family for theft-detection revocation.
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /**
     * Atomic compare-and-swap: only revokes+chains a token that is still
     * active. The return value (0 or 1 rows) tells the caller whether it
     * won a concurrent rotation race for the same token — see AuthService.
     * This is what stops two parallel refreshes of the same token both
     * succeeding and forking divergent token families.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :revokedAt, rt.replacedBy = :replacedBy "
            + "WHERE rt.id = :id AND rt.revokedAt IS NULL")
    int rotateIfActive(@Param("id") UUID id, @Param("revokedAt") Instant revokedAt, @Param("replacedBy") UUID replacedBy);

    /**
     * The rotation grace-window test, as one statement so it reads committed
     * state rather than a stale persistence-context copy (it runs straight
     * after {@link #rotateIfActive} loses a race). Non-zero only when the
     * token was retired <em>by rotation</em> (it has a successor), at or after
     * {@code rotatedAfter}, and that successor is still live. A token revoked
     * by logout, password reset/change or a family revoke has no successor,
     * or a revoked one, so it never qualifies. See AGENTS.md.
     */
    @Query("SELECT COUNT(rt) FROM RefreshToken rt, RefreshToken successor "
            + "WHERE rt.id = :id AND rt.replacedBy = successor.id "
            + "AND rt.revokedAt >= :rotatedAfter AND successor.revokedAt IS NULL")
    long countRotatedSinceWithLiveSuccessor(@Param("id") UUID id, @Param("rotatedAfter") Instant rotatedAfter);

    /**
     * Logout's revoke — atomic for the same reason as rotation: a
     * load-then-save racing a concurrent refresh would write back a null
     * {@code replaced_by} over the rotation.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :revokedAt WHERE rt.id = :id AND rt.revokedAt IS NULL")
    int revokeIfActive(@Param("id") UUID id, @Param("revokedAt") Instant revokedAt);

    /**
     * Hard-deletes rows that expired before {@code cutoff}. The
     * {@code NOT EXISTS} keeps any row a surviving row still points at via
     * {@code replaced_by}, so the self-FK can never be violated — rather than
     * relying on successors always expiring later, which stops being true the
     * moment the refresh TTL is shortened. One statement, so rows deleted
     * together may reference each other freely (the FK is checked at the end).
     * Native, so it ignores the {@code deleted} flag — these are dead
     * credentials, not domain history.
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens rt WHERE rt.expires_at < :cutoff "
            + "AND NOT EXISTS (SELECT 1 FROM refresh_tokens keeper "
            + "WHERE keeper.replaced_by = rt.id AND keeper.expires_at >= :cutoff)",
            nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
