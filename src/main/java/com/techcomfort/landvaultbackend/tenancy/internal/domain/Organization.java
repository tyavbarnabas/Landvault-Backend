package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.CompanyType;
import com.techcomfort.landvaultbackend.tenancy.internal.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.TenantStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.VerificationState;
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
 * A land-developer company — "tenant" is the architectural term (an
 * isolated customer of the platform), "organization" is the domain term
 * (Estintin Group, a real company with a CAC number). This entity is what
 * the rest of the schema means by "the tenant": other modules point at it
 * via a column literally named {@code tenant_id} (see {@link AbstractEntity}),
 * even though the table it points to is called {@code organizations}.
 * <p>
 * This row <b>is</b> the tenant, so {@link AbstractEntity#getTenantId()} and
 * {@link AbstractEntity#getBranchId()} stay {@code null} on every
 * {@code Organization} — it doesn't belong to another tenant, and it isn't
 * scoped to one of its own branches. Its relationship to {@link Branch} is
 * instead the explicit, purpose-named {@code organization_id} FK on
 * {@code Branch}.
 * <p>
 * <b>Two independent status axes — do not collapse them:</b>
 * <ul>
 *   <li>{@link #status} ({@link TenantStatus}) — can this tenant's staff use
 *   the portal at all?</li>
 *   <li>{@link #verificationState} ({@link VerificationState}) — can this
 *   tenant publish to the marketplace or collect payments?</li>
 * </ul>
 * A tenant can legitimately be {@code ACTIVE} + {@code UNDER_REVIEW}
 * (exploring the portal, setting up estates, while compliance review is
 * pending) or {@code VERIFIED} + {@code SUSPENDED} (compliant but suspended
 * for non-payment, e.g. Northbridge Estates in the frontend's seed data).
 * Collapsing these into one column would make both states unrepresentable.
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
