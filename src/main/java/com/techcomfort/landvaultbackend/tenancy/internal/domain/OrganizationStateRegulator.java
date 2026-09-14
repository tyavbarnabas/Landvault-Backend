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
 * One state-level real estate regulator an {@link Organization} is
 * registered with — several Nigerian states have introduced their own.
 * <p>
 * <b>LASRERA is not structurally special.</b> It is simply a row with
 * {@code state = "Lagos"} and {@code regulatorName = "LASRERA"}. There is
 * deliberately no dedicated LASRERA column or table — the onboarding wizard
 * merely pre-fills one of these rows when Lagos is among the organization's
 * states of operation.
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
        name = "organization_state_regulators",
        indexes = {
                @Index(name = "idx_organization_state_regulators_organization_id", columnList = "organization_id")
        }
)
@SQLRestriction("deleted = false")
public class OrganizationStateRegulator extends AbstractEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "state", nullable = false, length = 100)
    private String state;

    @Column(name = "regulator_name", nullable = false)
    private String regulatorName;

    @Column(name = "registration_number", nullable = false)
    private String registrationNumber;

    // Nullable: a supporting document may not have been uploaded yet even
    // though the registration itself is already on record.
    @Column(name = "document_id")
    private UUID documentId;

    @Override
    public void prePersist() {
        super.prePersist();
        if (getTenantId() == null) {
            setTenantId(organizationId);
        }
    }
}
