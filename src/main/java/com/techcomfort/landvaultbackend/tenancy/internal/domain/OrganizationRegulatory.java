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
 * An {@link Organization}'s company-level regulatory registrations (1:1).
 * redanNumber is a credibility signal, not a licence — see AGENTS.md.
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
