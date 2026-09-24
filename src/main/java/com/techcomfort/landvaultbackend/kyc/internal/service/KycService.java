package com.techcomfort.landvaultbackend.kyc.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.VerificationSource;
import com.techcomfort.landvaultbackend.identity.BuyerKycStatusProvider;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.kyc.KycApi;
import com.techcomfort.landvaultbackend.kyc.dto.KycDecisionRequest;
import com.techcomfort.landvaultbackend.kyc.dto.KycDocumentDto;
import com.techcomfort.landvaultbackend.kyc.dto.KycRecordDto;
import com.techcomfort.landvaultbackend.kyc.dto.SubmitKycRequest;
import com.techcomfort.landvaultbackend.kyc.internal.domain.KycDocument;
import com.techcomfort.landvaultbackend.kyc.internal.domain.KycRecord;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycBuyerType;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycDocType;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycDocumentStatus;
import com.techcomfort.landvaultbackend.kyc.internal.enums.KycStatus;
import com.techcomfort.landvaultbackend.kyc.internal.exceptions.KycException;
import com.techcomfort.landvaultbackend.kyc.internal.repository.KycDocumentRepository;
import com.techcomfort.landvaultbackend.kyc.internal.repository.KycRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Purchase-time identity verification: what a buyer must submit, what they
 * have submitted, and a reviewer's decision on it.
 * <p>
 * Implements both this module's own {@link KycApi} and {@code identity}'s
 * {@link BuyerKycStatusProvider} — the latter is how the login response
 * reports a real status without {@code identity} depending on this module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycService implements KycApi, BuyerKycStatusProvider {

    private final KycRecordRepository recordRepository;
    private final KycDocumentRepository documentRepository;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;

    // ------------------------------------------------------------------
    // Cross-module surfaces
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public boolean isVerified(UUID userId) {
        return recordRepository.findByUserId(userId)
                .map(record -> record.getStatus() == KycStatus.APPROVED)
                .orElse(false);
    }

    /**
     * Empty when the buyer has never submitted. The caller renders that as
     * {@code unsubmitted} rather than this method inventing a row — an
     * unverified buyer genuinely has no record.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> kycStatusOf(UUID userId) {
        return recordRepository.findByUserId(userId).map(record -> record.getStatus().getValue());
    }

    // ------------------------------------------------------------------
    // The buyer's own surface
    // ------------------------------------------------------------------

    /**
     * What this buyer must produce and where each document stands. Writes
     * nothing: a buyer who has never submitted gets the required set with
     * every document {@code missing}, and no row is created for merely
     * looking.
     */
    @Transactional(readOnly = true)
    public KycRecordDto statusFor(UUID userId) {
        KycBuyerType buyerType = buyerTypeOf(userId);
        return recordRepository.findByUserId(userId)
                .map(record -> toDto(record, documentRepository.findByKycRecordId(record.getId())))
                .orElseGet(() -> unsubmitted(buyerType));
    }

    /**
     * Records a submission, or a resubmission after a rejection.
     * <p>
     * A resubmission deliberately reuses the existing record and only
     * replaces the documents it carries: KY-3 requires that a rejection be
     * fixable by re-uploading the one document that failed, not by starting
     * the whole verification again.
     */
    @Transactional
    public KycRecordDto submit(UUID userId, SubmitKycRequest request) {
        KycBuyerType buyerType = buyerTypeOf(userId);
        Instant now = Instant.now();

        KycRecord record = recordRepository.findByUserId(userId).orElseGet(() -> KycRecord.builder()
                .userId(userId)
                .buyerType(buyerType)
                .status(KycStatus.UNSUBMITTED)
                .build());

        if (record.getStatus() == KycStatus.APPROVED) {
            // Nothing to redo. Silently re-opening an approved verification
            // on a stray POST would strip a buyer of the clearance they
            // already hold and block a purchase mid-flow.
            return toDto(record, documentRepository.findByKycRecordId(record.getId()));
        }

        List<KycDocument> existing = record.getId() == null
                ? List.of()
                : documentRepository.findByKycRecordId(record.getId());

        // Validated before anything is written, so a rejected submission
        // leaves no half-built record behind.
        List<SubmittedDocument> submitted = collectSubmitted(buyerType, request, existing);

        if (buyerType == KycBuyerType.LOCAL) {
            if (request.ninNumber() == null || request.ninNumber().isBlank()) {
                throw new KycException.MissingDocuments("An NIN is required.");
            }
            record.setNinNumber(request.ninNumber().trim());
        }

        record.setBuyerType(buyerType);
        record.setStatus(KycStatus.SUBMITTED);
        record.setSubmittedAt(now);
        // A resubmission is undecided again: the previous rejection is no
        // longer the current state of this record.
        record.setDecidedAt(null);
        record.setDecidedByUserId(null);
        record.setVerificationSource(null);
        KycRecord saved = recordRepository.save(record);

        for (SubmittedDocument doc : submitted) {
            KycDocument entity = doc.existing() != null ? doc.existing() : KycDocument.builder()
                    .kycRecordId(saved.getId())
                    .docType(doc.type())
                    .build();
            entity.setStatus(KycDocumentStatus.SUBMITTED);
            entity.setFileName(doc.fileName());
            entity.setFileSize(doc.fileSize());
            entity.setStorageKey(doc.storageKey());
            entity.setRejectionReason(null);
            entity.setSubmittedAt(now);
            documentRepository.save(entity);
        }

        // The fact, never the contents: no file name, no NIN, nothing that
        // would put regulated data in a log.
        log.info("KYC submitted by buyer {} ({} document set)", userId, buyerType.getValue());
        auditApi.record(AuditEntryRequest.of(
                userId, "kyc.submitted", "kyc_record", saved.getId(), null,
                "Identity documents submitted for " + buyerType.getValue() + " verification"));

        return toDto(saved, documentRepository.findByKycRecordId(saved.getId()));
    }

    // ------------------------------------------------------------------
    // The reviewer's surface
    // ------------------------------------------------------------------

    /**
     * Manual review, by a human, recorded as such.
     * <p>
     * Beyond the slice's stated scope and built anyway, because without it
     * nothing can ever reach {@code approved}: reservations require a
     * verified buyer, so a submission with no way to be decided would leave
     * the whole purchase path unreachable, and every per-document rejection
     * column dead. Automated verification against NIMC remains out of scope
     * — {@code verificationSource} is what will keep the two distinguishable.
     */
    @Transactional
    public KycRecordDto decide(UUID userId, UUID reviewerUserId, KycDecisionRequest request) {
        KycRecord record = recordRepository.findByUserId(userId)
                .orElseThrow(KycException.RecordNotFound::new);

        if (record.getStatus() == KycStatus.UNSUBMITTED) {
            throw new KycException.InvalidDecision("That buyer has not submitted anything to review.");
        }

        boolean approved = switch (request.decision().trim().toLowerCase()) {
            case "approved" -> true;
            case "rejected" -> false;
            default -> throw new KycException.InvalidDecision(
                    "Decision must be 'approved' or 'rejected', got '" + request.decision() + "'.");
        };

        List<KycDocument> documents = documentRepository.findByKycRecordId(record.getId());
        Instant now = Instant.now();

        if (approved) {
            documents.forEach(doc -> {
                doc.setStatus(KycDocumentStatus.APPROVED);
                doc.setRejectionReason(null);
            });
        } else {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new KycException.InvalidDecision("A reason is required when rejecting.");
            }
            Set<KycDocType> failed = parseFailedTypes(request.failedDocumentTypes());
            if (failed.isEmpty()) {
                throw new KycException.InvalidDecision(
                        "A rejection must name which documents failed, so the buyer knows what to resubmit.");
            }
            Set<KycDocType> onRecord = documents.stream()
                    .map(KycDocument::getDocType)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            for (KycDocType type : failed) {
                if (!onRecord.contains(type)) {
                    throw new KycException.InvalidDecision(
                            "That buyer was not asked for a " + type.getValue() + ".");
                }
            }
            // Every other document is approved by the same decision — the
            // same cascade a tenant verification decision already applies.
            documents.forEach(doc -> {
                if (failed.contains(doc.getDocType())) {
                    doc.setStatus(KycDocumentStatus.REJECTED);
                    doc.setRejectionReason(request.reason());
                } else {
                    doc.setStatus(KycDocumentStatus.APPROVED);
                    doc.setRejectionReason(null);
                }
            });
        }
        documentRepository.saveAll(documents);

        record.setStatus(approved ? KycStatus.APPROVED : KycStatus.REJECTED);
        record.setDecidedAt(now);
        record.setDecidedByUserId(reviewerUserId);
        record.setVerificationSource(VerificationSource.MANUAL_REVIEW);
        KycRecord saved = recordRepository.save(record);

        log.info("KYC {} for buyer {} by reviewer {}",
                saved.getStatus().getValue(), userId, reviewerUserId);
        auditApi.record(AuditEntryRequest.of(
                reviewerUserId,
                approved ? "kyc.approved" : "kyc.rejected",
                "kyc_record", saved.getId(), null,
                approved ? "Identity verification approved (manual review)"
                        : "Identity verification rejected (manual review)"));

        return toDto(saved, documents);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Residence decides the document set, and it is read from the buyer's
     * own account — never from the request. A buyer choosing their own
     * verification requirements would be choosing the weaker one.
     */
    private KycBuyerType buyerTypeOf(UUID userId) {
        return identityApi.countryOf(userId)
                .map(KycBuyerType::forCountry)
                .orElseThrow(KycException.BuyerNotFound::new);
    }

    private List<SubmittedDocument> collectSubmitted(
            KycBuyerType buyerType, SubmitKycRequest request, List<KycDocument> existing) {

        List<SubmittedDocument> submitted = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (KycDocType required : requiredDocuments(buyerType)) {
            SubmitKycRequest.KycFileRef file = switch (required) {
                case NIN -> request.ninFile();
                case PASSPORT -> request.passportFile();
                case PROOF_OF_ADDRESS -> request.proofOfAddressFile();
            };
            KycDocument already = existing.stream()
                    .filter(doc -> doc.getDocType() == required)
                    .findFirst()
                    .orElse(null);

            boolean provided = file != null && file.fileName() != null && !file.fileName().isBlank();
            boolean heldFromBefore = already != null && already.getStatus() != KycDocumentStatus.MISSING
                    && already.getStatus() != KycDocumentStatus.REJECTED;

            if (provided) {
                submitted.add(new SubmittedDocument(
                        required, file.fileName(), file.fileSize(), file.storageKey(), already));
            } else if (!heldFromBefore) {
                // Missing outright, or the one that was just rejected and
                // still hasn't been replaced.
                missing.add(required.getValue());
            }
        }

        if (!missing.isEmpty()) {
            throw new KycException.MissingDocuments(
                    "Missing required document(s) for a " + buyerType.getValue() + " buyer: "
                            + String.join(", ", missing) + ".");
        }
        return submitted;
    }

    /**
     * A local buyer submits an NIN and nothing else — deliberately not a
     * utility bill on top. A diaspora buyer, with no NIN to give, submits a
     * passport and proof of address.
     */
    private static List<KycDocType> requiredDocuments(KycBuyerType buyerType) {
        return buyerType == KycBuyerType.LOCAL
                ? List.of(KycDocType.NIN)
                : List.of(KycDocType.PASSPORT, KycDocType.PROOF_OF_ADDRESS);
    }

    private static Set<KycDocType> parseFailedTypes(List<String> wireValues) {
        if (wireValues == null) {
            return Set.of();
        }
        // LinkedHashSet, not Set.of(...): the caller may legitimately repeat
        // a type, and Set.of throws on a duplicate element.
        Set<KycDocType> types = new LinkedHashSet<>();
        wireValues.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(KycDocType::fromValue)
                .forEach(types::add);
        return types;
    }

    /** The honest shape of "nothing submitted": required documents, all missing. */
    private static KycRecordDto unsubmitted(KycBuyerType buyerType) {
        List<KycDocumentDto> documents = requiredDocuments(buyerType).stream()
                .map(type -> new KycDocumentDto(
                        type.getValue(), KycDocumentStatus.MISSING.getValue(), null, null))
                .toList();
        return new KycRecordDto(
                buyerType.getValue(), KycStatus.UNSUBMITTED.getValue(), documents, null, null);
    }

    private static KycRecordDto toDto(KycRecord record, List<KycDocument> documents) {
        List<KycDocumentDto> documentDtos = documents.stream()
                .sorted(Comparator.comparing(doc -> doc.getDocType().ordinal()))
                .map(doc -> new KycDocumentDto(
                        doc.getDocType().getValue(),
                        doc.getStatus().getValue(),
                        doc.getFileName(),
                        doc.getRejectionReason()))
                .toList();
        return new KycRecordDto(
                record.getBuyerType().getValue(),
                record.getStatus().getValue(),
                documentDtos,
                record.getSubmittedAt(),
                record.getDecidedAt());
    }

    /** One document as it arrived, paired with the row it replaces, if any. */
    private record SubmittedDocument(
            KycDocType type, String fileName, Long fileSize, String storageKey, KycDocument existing) {
    }
}
