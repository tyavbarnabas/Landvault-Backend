package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.inventory.internal.enums.VerificationCheckStatus;
import com.techcomfort.landvaultbackend.inventory.internal.enums.VerificationCheckType;
import com.techcomfort.landvaultbackend.inventory.internal.enums.VerificationSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * One due-diligence check on an estate — AGIS registration, encroachment
 * status, title verification.
 * <p>
 * <strong>{@code NOT_CHECKED} is the default and must never render as
 * positive.</strong> The absence of a check is not a clean bill of health;
 * the frontend removed hardcoded "No encroachment notices on file" claims for
 * exactly this reason. A row that does not exist means nobody has looked.
 * <p>
 * {@code verificationSource} is what makes this worth more than a boolean: a
 * green badge from a real registry call and a green badge from a human
 * reading a PDF are different claims, and a buyer deciding whether to part
 * with money deserves to know which they are looking at.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "estate_verification_checks",
        indexes = {
                @Index(name = "idx_estate_verification_checks_estate_id", columnList = "estate_id"),
                @Index(name = "idx_estate_verification_checks_tenant_id", columnList = "tenant_id")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_estate_verification_checks_estate_type", columnNames = {"estate_id", "check_type"})
)
@SQLRestriction("deleted = false")
public class EstateVerificationCheck extends AbstractEntity {

    @Column(name = "estate_id", nullable = false)
    private UUID estateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 40)
    private VerificationCheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VerificationCheckStatus status;

    /** Nullable while {@code status} is {@code NOT_CHECKED} — nothing produced a result yet. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_source", length = 32)
    private VerificationSource verificationSource;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Override
    public void prePersist() {
        super.prePersist();
        if (status == null) {
            status = VerificationCheckStatus.NOT_CHECKED;
        }
    }
}
