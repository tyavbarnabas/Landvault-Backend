package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitleDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EstateTitleDocumentRepository extends JpaRepository<EstateTitleDocument, UUID> {

    Optional<EstateTitleDocument> findByFileId(UUID fileId);

    Optional<EstateTitleDocument> findByEstateIdAndFileId(UUID estateId, UUID fileId);

    List<EstateTitleDocument> findByFileIdIn(Collection<UUID> fileIds);
}
