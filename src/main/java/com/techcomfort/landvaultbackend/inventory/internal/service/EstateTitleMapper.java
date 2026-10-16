package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.TitleDocumentDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitleDocument;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateTitleDocumentRepository;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import com.techcomfort.landvaultbackend.storage.StoredFileInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Titles to their wire shape, with their documents. Batched: two queries however many titles. */
@Component
@RequiredArgsConstructor
class EstateTitleMapper {

    private final EstateTitleDocumentRepository documents;
    private final StorageApi storage;

    EstateTitleDto toDto(EstateTitle title) {
        return toDtos(List.of(title)).getFirst();
    }

    /** In the order given. */
    List<EstateTitleDto> toDtos(List<EstateTitle> titles) {
        List<UUID> fileIds = titles.stream().flatMap(EstateTitleMapper::attached).toList();
        if (fileIds.isEmpty()) {
            return titles.stream().map(t -> toDto(t, List.of())).toList();
        }
        Map<UUID, EstateTitleDocument> docs = documents.findByFileIdIn(fileIds).stream()
                .collect(Collectors.toMap(EstateTitleDocument::getFileId, Function.identity()));
        Map<UUID, StoredFileInfo> files = storage.findAll(fileIds).stream()
                .collect(Collectors.toMap(StoredFileInfo::id, Function.identity()));
        return titles.stream().map(t -> {
            List<TitleDocumentDto> list = new ArrayList<>();
            attached(t).forEach(id -> {
                if (docs.containsKey(id) && files.containsKey(id)) {
                    list.add(toDto(docs.get(id), files.get(id)));
                }
            });
            return toDto(t, list);
        }).toList();
    }

    static TitleDocumentDto toDto(EstateTitleDocument doc, StoredFileInfo file) {
        return new TitleDocumentDto(doc.getFileId(), doc.getKind().getValue(), file.fileName(),
                file.fileType().getValue(), file.sizeBytes(), doc.getCreatedAt());
    }

    /** The title page, survey plan and deed, in that order, as far as they are set. */
    static Stream<UUID> attached(EstateTitle t) {
        return Stream.of(t.getTitleDocumentFileId(), t.getSurveyPlanFileId(), t.getDeedFileId()).filter(Objects::nonNull);
    }

    private static EstateTitleDto toDto(EstateTitle t, List<TitleDocumentDto> docs) {
        return new EstateTitleDto(t.getId(), t.getEstateId(), t.getTitleType().getValue(), t.getTitleNumber(),
                t.getIssuedDate(), t.getStatus().getValue(), docs, t.getSubmittedAt(), t.getSubmittedBy(),
                t.getDecidedAt(), t.getDecidedBy(),
                t.getVerificationSource() == null ? null : t.getVerificationSource().getValue(), t.getDecisionNote());
    }
}
