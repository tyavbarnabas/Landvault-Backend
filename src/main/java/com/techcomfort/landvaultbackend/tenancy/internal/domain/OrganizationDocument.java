package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.DocumentStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.OrganizationDocumentType;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A corporate document an {@link Organization} submitted for verification —
 * CAC certificate, CAC status report / Memart, TIN, proof of address, SCUML
 * certificate, a state regulator permit, REDAN certificate.
 * <p>
 * <b>Land title documents (C of O, R of O, Governor's Consent, Gazette,
 * survey plan) are deliberately NOT stored here.</b> Title evidence is
 * per-estate, not per-company — a company can hold clean title on one
 * estate and none on another. Title lives on the estate record and is
 * captured at estate creation, not here.
 * <p>
 * Unlike {@link Organization} (which <i>is</i> the tenant) and {@link Branch}
 * (whose parent is the organization), this record belongs to a specific
 * tenant, so {@link AbstractEntity#getTenantId()} is populated — set equal
 * to {@link #getOrganizationId()} — rather than left null.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "organization_documents",
        indexes = {
                @Index(name = "idx_organization_documents_organization_id", columnList = "organization_id")
        }
)
@SQLRestriction("deleted = false")
public class OrganizationDocument extends AbstractEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private OrganizationDocumentType type;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "size", nullable = false)
    private Long size;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DocumentStatus status;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    // TODO: no file storage (S3/MinIO) exists yet — only metadata is
    // persisted today. This will hold the eventual object key once a
    // storage integration lands.
    @Column(name = "storage_key")
    private String storageKey;

    @Override
    public void prePersist() {
        super.prePersist();
        if (getTenantId() == null) {
            setTenantId(organizationId);
        }
    }
}
