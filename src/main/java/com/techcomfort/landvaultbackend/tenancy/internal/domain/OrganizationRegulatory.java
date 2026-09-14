package com.techcomfort.landvaultbackend.tenancy.internal.domain;

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

import java.util.UUID;

/**
 * The company-level regulatory registrations for one {@link Organization} —
 * a 1:1 record (enforced by a unique constraint on {@code organization_id}).
 * <p>
 * {@code scumlNumber} matters because real estate firms are Designated
 * Non-Financial Businesses under Nigeria's AML regime and are generally
 * required to register with SCUML under the EFCC — the platform facilitates
 * large property payments, so this isn't paperwork for its own sake.
 * <p>
 * {@code redanNumber} is a <b>credibility signal, not a licence</b> — REDAN
 * membership must never be treated as a verification requirement.
 * <p>
 * Unlike {@link Organization} (which <i>is</i> the tenant), this record
 * belongs to a specific tenant, so {@link AbstractEntity#getTenantId()} is
 * populated — set equal to {@link #getOrganizationId()} — rather than left
 * null.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "organization_regulatory",
        indexes = {
                @Index(name = "idx_organization_regulatory_organization_id", columnList = "organization_id")
        }
)
@SQLRestriction("deleted = false")
public class OrganizationRegulatory extends AbstractEntity {

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(name = "scuml_number", nullable = false)
    private String scumlNumber;

    // Nullable: a SCUML certificate may not have been uploaded yet even
    // though the number itself is already on record.
    @Column(name = "scuml_document_id")
    private UUID scumlDocumentId;

    @Column(name = "redan_number")
    private String redanNumber;

    @Override
    public void prePersist() {
        super.prePersist();
        if (getTenantId() == null) {
            setTenantId(organizationId);
        }
    }
}
