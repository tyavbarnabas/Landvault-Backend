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
 * A corporate verification document for an {@link Organization} (CAC
 * certificate, TIN, SCUML certificate, etc.) — NOT land title documents,
 * which are per-estate. See AGENTS.md.
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
