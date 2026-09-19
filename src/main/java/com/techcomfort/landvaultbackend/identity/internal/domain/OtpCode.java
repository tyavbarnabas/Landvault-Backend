package com.techcomfort.landvaultbackend.identity.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.identity.internal.enums.OtpChannel;
import com.techcomfort.landvaultbackend.identity.internal.enums.OtpPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A one-time code, discriminated by {@link OtpPurpose}. Stores only
 * {@code codeHash}, never the code itself — see AGENTS.md for why SHA-256
 * rather than BCrypt here, and why {@code consumedAt} marks every terminal
 * state, not just successful use.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "otp_codes",
        indexes = {
                @Index(name = "idx_otp_codes_user_id_purpose", columnList = "user_id, purpose")
        }
)
@SQLRestriction("deleted = false")
public class OtpCode extends AbstractEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Never the raw code — a leaked hash isn't a usable credential.
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 40)
    private OtpPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private OtpChannel channel;

    // Captured at send time, not looked up from the user at verify time —
    // the user's email may change between request and use.
    @Column(name = "destination", nullable = false)
    private String destination;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Set once this code can never be used again — whether that's because it
     * was successfully used, or because a newer code superseded it. Attempt
     * exhaustion is the third terminal state and is <em>not</em> recorded
     * here: it's already visible as {@code attemptCount} reaching the limit,
     * so nothing is lost by leaving this null in that case.
     */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;
}
