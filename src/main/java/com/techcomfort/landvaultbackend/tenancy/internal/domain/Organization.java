package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.CompanyType;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationState;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;

/**
 * A land-developer company — the tenant itself, so tenantId/branchId stay
 * null. See AGENTS.md for the organizations/tenant_id naming split and the
 * two independent status axes (status vs. verificationState).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "organizations")
@SQLRestriction("deleted = false")
public class Organization extends AbstractEntity {

    // --- Company identity (frontend CompanyIdentity) ---

    @Column(name = "registered_name", nullable = false)
    private String registeredName;

    @Column(name = "trading_name")
    private String tradingName;

    @Column(name = "rc_number", nullable = false)
    private String rcNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "company_type", nullable = false, length = 64)
    private CompanyType companyType;

    @Column(name = "date_of_incorporation", nullable = false)
    private LocalDate dateOfIncorporation;

    // --- Registered address ---

    @Column(name = "registered_street", nullable = false)
    private String registeredStreet;

    @Column(name = "registered_city", nullable = false)
    private String registeredCity;

    @Column(name = "registered_state", nullable = false, length = 100)
    private String registeredState;

    // --- Operating address ---

    @Column(name = "operating_street", nullable = false)
    private String operatingStreet;

    @Column(name = "operating_city", nullable = false)
    private String operatingCity;

    @Column(name = "operating_state", nullable = false, length = 100)
    private String operatingState;

    // --- Presence (frontend CompanyPresence) ---

    @Column(name = "company_email", nullable = false)
    private String companyEmail;

    @Column(name = "company_phone", nullable = false)
    private String companyPhone;

    @Column(name = "website")
    private String website;

    @Column(name = "social_instagram")
    private String socialInstagram;

    @Column(name = "social_twitter")
    private String socialTwitter;

    @Column(name = "social_facebook")
    private String socialFacebook;

    @Column(name = "social_linkedin")
    private String socialLinkedin;

    // --- Plan and entitlements ---

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 32)
    private TenantPlan plan;

    @Column(name = "marketplace_publishing", nullable = false)
    private Boolean marketplacePublishing;

    @Column(name = "mlm_module", nullable = false)
    private Boolean mlmModule;

    @Column(name = "fx_rails", nullable = false)
    private Boolean fxRails;

    // --- The two independent status  ---

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TenantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 32)
    private VerificationState verificationState;
}
