package com.techcomfort.landvaultbackend.kyc.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycDocType;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycDocumentStatus;
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

import java.time.Instant;
import java.util.UUID;

/**
 * One document a buyer was asked for, with its own status and rejection
 * reason so a rejection names which evidence failed. See AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "kyc_documents")
@SQLRestriction("deleted = false")
public class KycDocument extends AbstractEntity {

    @Column(name = "kyc_record_id", nullable = false, updatable = false)
    private UUID kycRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 24)
    private KycDocType docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private KycDocumentStatus status;

    /** Metadata only — file storage is not built; see the changeset. */
    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;
}
