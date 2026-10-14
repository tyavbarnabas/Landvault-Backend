package com.techcomfort.landvaultbackend.storage.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.storage.FileType;
import com.techcomfort.landvaultbackend.storage.StorageVisibility;
import com.techcomfort.landvaultbackend.storage.internal.enums.StoredFileStatus;
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

/** One uploaded file in object storage (changeset 081). The record outlives the object. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "stored_files")
@SQLRestriction("deleted = false")
public class StoredFile extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 8)
    private StorageVisibility visibility;

    @Column(name = "bucket", nullable = false, length = 63)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "purpose", nullable = false, length = 40)
    private String purpose;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 8)
    private FileType fileType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private StoredFileStatus status;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "upload_link_expires_at", nullable = false)
    private Instant uploadLinkExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "removed_at")
    private Instant removedAt;
}
