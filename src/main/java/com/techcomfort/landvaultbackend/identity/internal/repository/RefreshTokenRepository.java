package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
