package com.techcomfort.landvaultbackend.kyc.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.VerificationSource;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycBuyerType;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycStatus;
import com.techcomfort.landvaultbackend.kyc.internal.security.KycNinConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One buyer's purchase-time identity verification, held at platform level —
 * the inherited {@code tenantId}/{@code branchId} stay null on every row so
 * a buyer verifies once and can transact with any company. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "kyc_records")
@SQLRestriction("deleted = false")
public class KycRecord extends AbstractEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "buyer_type", nullable = false, length = 16)
    private KycBuyerType buyerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private KycStatus status;

    /**
     * Encrypted at rest by {@link KycNinConverter} — the column holds
     * ciphertext, never eleven digits. No API returns this value, not even
     * masked: nothing needs it yet, and the safest handling of regulated
     * data nobody reads is not to hand it out at all.
     */
    @Convert(converter = KycNinConverter.class)
    @Column(name = "nin_number")
    private String ninNumber;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    /**
     * Always {@link VerificationSource#MANUAL_REVIEW} today. The column
     * exists so a future NIMC/QoreID integration is distinguishable from a
     * human reading a scan — a distinction a buyer deciding whether to part
     * with money deserves, and the same reason
     * {@code estate_verification_checks} carries one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_source", length = 24)
    private VerificationSource verificationSource;
}
