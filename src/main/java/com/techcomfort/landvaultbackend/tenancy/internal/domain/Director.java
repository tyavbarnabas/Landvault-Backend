package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.GovIdType;
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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A director or beneficial owner of an {@link Organization}, captured
 * during onboarding and cross-checked against the CAC status report.
 * <p>
 * <b>This is the most sensitive data in the system.</b> {@code idNumber}
 * and {@code bvn} are NDPR-regulated personal data — see the table comment
 * carried into the schema by this entity's changelog for the standing
 * TODOs (encrypt at rest, restrict reads to compliance staff, log every
 * read, define a retention policy). None of those are implemented yet;
 * this entity only carries the columns and the warning.
 * <p>
 * {@code fullName} is a single field, deliberately, unlike {@code User}
 * which splits first/last name — a director's name is transcribed from the
 * CAC register as one registered string, and splitting it would invent a
 * split the source document doesn't make. Do not "fix" this for consistency
 * with {@code User}.
 * <p>
 * <b>{@code isBeneficialOwner} is stored, not computed.</b> The threshold is
 * 25% ownership (standard AML practice — it protects the platform from
 * onboarding a front company), but the flag is a compliance assertion made
 * at a point in time. If {@code ownershipPct} later changes, the historical
 * record of who was flagged when must not silently rewrite itself by
 * re-deriving the flag live.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "directors",
        indexes = {
                @Index(name = "idx_directors_organization_id", columnList = "organization_id")
        }
)
@SQLRestriction("deleted = false")
public class Director extends AbstractEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "nationality", nullable = false)
    private String nationality;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", nullable = false, length = 32)
    private GovIdType idType;

    // SENSITIVE — NDPR-regulated. See class Javadoc and the table comment.
    @Column(name = "id_number", nullable = false)
    private String idNumber;

    // SENSITIVE — NDPR-regulated. See class Javadoc and the table comment.
    @Column(name = "bvn")
    private String bvn;

    @Column(name = "ownership_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal ownershipPct;

    @Column(name = "is_beneficial_owner", nullable = false)
    private Boolean isBeneficialOwner;

    @Override
    public void prePersist() {
        super.prePersist();
        if (getTenantId() == null) {
            setTenantId(organizationId);
        }
    }
}
