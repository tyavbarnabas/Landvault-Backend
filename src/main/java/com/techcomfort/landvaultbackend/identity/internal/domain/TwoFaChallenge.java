package com.techcomfort.landvaultbackend.identity.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending login: credentials verified, second factor still outstanding.
 * <p>
 * Carries <strong>no authority</strong> — it cannot authenticate a request,
 * it only identifies which login a subsequent code belongs to. Stored rather
 * than issued as a self-contained signed token because it must be single-use,
 * and a signed token is replayable until it expires. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "two_fa_challenges",
        indexes = @Index(name = "idx_two_fa_challenges_user_id", columnList = "user_id")
)
@SQLRestriction("deleted = false")
public class TwoFaChallenge extends AbstractEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Never the raw token — same rule as refresh_tokens.token_hash.
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
