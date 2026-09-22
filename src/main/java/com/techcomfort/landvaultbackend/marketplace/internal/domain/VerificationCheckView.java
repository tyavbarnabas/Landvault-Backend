package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import com.techcomfort.landvaultbackend.common.VerificationCheckStatus;
import com.techcomfort.landvaultbackend.common.VerificationCheckType;
import com.techcomfort.landvaultbackend.common.VerificationSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** A row of {@code marketplace_verification_checks}. No notes: the view excludes them. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_verification_checks")
public class VerificationCheckView {

    @Id
    @Column(name = "check_id")
    private UUID checkId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type")
    private VerificationCheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VerificationCheckStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_source")
    private VerificationSource verificationSource;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;
}
