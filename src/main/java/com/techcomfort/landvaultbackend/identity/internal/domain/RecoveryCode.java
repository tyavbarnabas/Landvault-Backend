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
 * A single-use code that stands in for a TOTP code when the user has lost
 * their device. Without these, TOTP is a one-way door — see AGENTS.md.
 * Stores only {@code codeHash}; the plaintext is shown once, at issue.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "recovery_codes",
        indexes = @Index(name = "idx_recovery_codes_user_id", columnList = "user_id")
)
@SQLRestriction("deleted = false")
public class RecoveryCode extends AbstractEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    // Single use — set the moment it's accepted, never cleared.
    @Column(name = "consumed_at")
    private Instant consumedAt;
}
