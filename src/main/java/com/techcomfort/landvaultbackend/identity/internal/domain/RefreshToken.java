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
 * A session's refresh token — stores only {@code tokenHash}, never the raw
 * token. {@code replacedBy} chains rotations.
 * <p>
 * TODO: the frontend currently holds the access token in localStorage (a
 * flagged XSS exposure). Moving refresh tokens to httpOnly cookies is the
 * intended direction, pending backend cookie/CORS decisions not settled yet.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
                @Index(name = "idx_refresh_tokens_replaced_by", columnList = "replaced_by")
        }
)
@SQLRestriction("deleted = false")
public class RefreshToken extends AbstractEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Never the raw token — a leaked hash isn't a usable credential.
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    // Nullable self-FK: set when this token was rotated out for a newer one.
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "device")
    private String device;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_address")
    private String ipAddress;
}
