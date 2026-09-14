package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * Super Admin's guarded, time-boxed access into an {@link Organization}'s
 * view. Visibility only, never authority — see AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "support_access_grants",
        indexes = {
                @Index(name = "idx_support_access_grants_organization_id", columnList = "organization_id"),
                @Index(name = "idx_support_access_grants_granted_to_user_id", columnList = "granted_to_user_id")
        }
)
public class SupportAccessGrant extends AbstractAppendOnlyEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "granted_to_user_id", nullable = false)
    private UUID grantedToUserId;

    // Required, and shown in the audit log — never an optional field here.
    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
