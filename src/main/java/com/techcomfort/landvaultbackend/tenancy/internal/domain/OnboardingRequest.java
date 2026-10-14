package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.OnboardingRequestStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.PreferredContactMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A developer company asking to be onboarded. Grants nothing; see AGENTS.md, "Onboarding requests". */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "onboarding_requests")
@SQLRestriction("deleted = false")
public class OnboardingRequest extends AbstractEntity {

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "rc_number", length = 20)
    private String rcNumber;

    @Column(name = "company_address", length = 500)
    private String companyAddress;

    /** Canonical state names. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "states_of_operation", nullable = false, columnDefinition = "varchar(60)[]")
    private String[] statesOfOperation;

    @Column(name = "estimated_estate_count")
    private Integer estimatedEstateCount;

    @Column(name = "contact_name", nullable = false, length = 150)
    private String contactName;

    @Column(name = "contact_role", length = 100)
    private String contactRole;

    @Column(name = "contact_email", nullable = false, length = 254)
    private String contactEmail;

    @Column(name = "contact_phone", nullable = false, length = 30)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_contact_method", nullable = false, length = 10)
    private PreferredContactMethod preferredContactMethod;

    @Column(name = "preferred_contact_time", length = 200)
    private String preferredContactTime;

    @Column(name = "message", length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OnboardingRequestStatus status;

    @Column(name = "contacted_at")
    private Instant contactedAt;

    @Column(name = "contacted_by")
    private UUID contactedBy;

    @Column(name = "contact_note", length = 2000)
    private String contactNote;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decline_reason", length = 1000)
    private String declineReason;

    @Column(name = "organization_id")
    private UUID organizationId;
}
