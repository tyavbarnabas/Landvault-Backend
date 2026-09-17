package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.GovIdType;
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
 * A director/beneficial owner of an {@link Organization}. Most sensitive
 * table in the system (NDPR-regulated idNumber) — see AGENTS.md.
 * <p>
 * {@code bvn} was collected here once and removed (changeset 024): it was
 * never verified against anything, so it was liability without benefit —
 * see AGENTS.md for the full reasoning and the condition under which it
 * could legitimately return.
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
