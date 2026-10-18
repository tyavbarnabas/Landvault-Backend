package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadImageDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadOverviewDto;
import com.techcomfort.landvaultbackend.documents.dto.PrintedLetterheadDto;
import com.techcomfort.landvaultbackend.documents.dto.RequestLetterheadImageUploadRequest;
import com.techcomfort.landvaultbackend.documents.dto.SetSignatoryRequest;
import com.techcomfort.landvaultbackend.documents.internal.domain.Letterhead;
import com.techcomfort.landvaultbackend.documents.internal.enums.LetterheadImage;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.repository.LetterheadRepository;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.FileType;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import com.techcomfort.landvaultbackend.storage.StorageException;
import com.techcomfort.landvaultbackend.storage.StorageVisibility;
import com.techcomfort.landvaultbackend.storage.StoredFileInfo;
import com.techcomfort.landvaultbackend.storage.UploadRequest;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import com.techcomfort.landvaultbackend.tenancy.CompanyLetterheadDetails;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Letterheads (DS-1, DS-2): the company's, and any branch's own. Reads are for
 * anyone holding {@code portal.documents.view}, under their RLS scope; every
 * change needs {@code portal.documents.manage} and a company-wide scope, takes
 * the letterhead's row lock, and raises its version. A no-op changes nothing
 * and writes no audit entry. See AGENTS.md, "Documents: letterheads".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LetterheadService {

    /** 2 MB: a logo, a scanned signature or a seal. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    /** PNG for transparency; JPEG for a photo of paper. PDFBox draws both; WebP it can't. */
    private static final Set<FileType> ALLOWED = Set.of(FileType.PNG, FileType.JPEG);
    private static final String COMPANY = "company";
    private static final String BRANCH = "branch";

    private final LetterheadRepository letterheads;
    private final TenancyApi tenancy;
    private final StorageApi storage;
    private final AuditApi auditApi;

    // ------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public LetterheadOverviewDto overview() {
        UUID tenantId = PortalScopes.company(PortalScopes.current());
        CompanyLetterheadDetails details = details(tenantId);
        List<Letterhead> rows = letterheads.findByTenantId(tenantId);
        Letterhead company = rows.stream().filter(r -> r.getBranchId() == null).findFirst().orElse(null);
        Map<UUID, Letterhead> byBranch = rows.stream().filter(r -> r.getBranchId() != null)
                .collect(Collectors.toMap(Letterhead::getBranchId, Function.identity()));
        Map<UUID, StoredFileInfo> files = files(rows);

        LetterheadDto companyDto = toDto(details, null, company, null, files);
        List<LetterheadDto> branches = details.branches().stream()
                .map(office -> toDto(details, office, byBranch.get(office.id()), company, files))
                .toList();
        return new LetterheadOverviewDto(companyDto, branches);
    }

    /**
     * A five-minute link to one image on a letterhead the caller can see —
     * current images only. Signatures and seals are never given a public address.
     */
    @Transactional(readOnly = true)
    public DownloadLink imageLink(UUID fileId) {
        UUID tenantId = PortalScopes.company(PortalScopes.current());
        boolean onALetterhead = letterheads.findByTenantId(tenantId).stream()
                .flatMap(LetterheadService::fileIds)
                .anyMatch(fileId::equals);
        if (!onALetterhead) {
            throw new DocumentException.NotFound("LETTERHEAD_FILE_NOT_FOUND", "No letterhead image has this id.");
        }
        return storage.downloadLink(fileId);
    }

    // ------------------------------------------------------------- changes

    @Transactional
    public LetterheadDto setSignatory(String target, SetSignatoryRequest request) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        Target t = resolveTarget(tenantId, target);
        Letterhead row = lockOrCreate(tenantId, t.branchId());

        String name = request.name().strip();
        String title = request.title().strip();
        if (!Objects.equals(name, row.getSignatoryName()) || !Objects.equals(title, row.getSignatoryTitle())) {
            String before = describeSignatory(row);
            row.setSignatoryName(name);
            row.setSignatoryTitle(title);
            save(row, scope, "letterhead.signatory_set",
                    t.label() + ": signatory " + before + " → " + name + ", " + title + ".");
        }
        return reload(tenantId, t);
    }

    /**
     * Removes this letterhead's own signer as a unit — name, title and signature.
     * A branch then uses the company's signatory.
     */
    @Transactional
    public LetterheadDto clearSignatory(String target) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        Target t = resolveTarget(tenantId, target);
        Letterhead row = lockOrCreate(tenantId, t.branchId());
        if (row.hasOwnSignatory()) {
            String before = describeSignatory(row);
            row.setSignatoryName(null);
            row.setSignatoryTitle(null);
            row.setSignatureFileId(null);
            save(row, scope, "letterhead.signatory_cleared", t.label() + ": signatory " + before + " removed"
                    + (t.branchId() == null ? "." : "; the company's signatory applies."));
        }
        return reload(tenantId, t);
    }

    /** Permission for one image upload, into the PRIVATE bucket, with metadata removal asked for. */
    @Transactional
    public UploadTicket requestImageUpload(String target, RequestLetterheadImageUploadRequest request) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        resolveTarget(tenantId, target);
        LetterheadImage kind = imageKind(request.kind());
        // Metadata removed: a signature photographed on a phone carries where it was taken.
        return storage.requestUpload(new UploadRequest(StorageVisibility.PRIVATE, kind.storagePurpose(),
                request.fileName(), request.contentType(), request.sizeBytes(), ALLOWED, MAX_BYTES,
                scope.userId(), tenantId, true));
    }

    /**
     * Storage checks the file; it then becomes this letterhead's image of the
     * kind it was uploaded as, replacing any earlier one. The earlier file is
     * kept: letters already issued point at it.
     * <p>
     * {@code noRollbackFor}: storage marks a refused file rejected and deletes
     * it, then throws — rolling back here would undo the marking (the trap
     * recorded under "Estate photos").
     */
    @Transactional(noRollbackFor = StorageException.UploadRejected.class)
    public LetterheadDto confirmImage(String target, UUID fileId) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        Target t = resolveTarget(tenantId, target);
        Letterhead row = lockOrCreate(tenantId, t.branchId());

        StoredFileInfo file = storage.confirmUpload(fileId, scope.userId());
        LetterheadImage kind = LetterheadImage.fromStoragePurpose(file.purpose())
                .filter(k -> file.visibility() == StorageVisibility.PRIVATE)
                // Another kind of file the caller uploaded. Same answer as someone else's.
                .orElseThrow(StorageException.FileNotFound::new);
        if (!fileId.equals(image(row, kind))) {
            boolean replacing = image(row, kind) != null;
            setImage(row, kind, fileId);
            save(row, scope, "letterhead.image_set", t.label() + ": " + kind.value()
                    + (replacing ? " replaced." : " added."));
        }
        return reload(tenantId, t);
    }

    /** The letterhead stops using this image; a branch then uses the company's. The file is kept. */
    @Transactional
    public LetterheadDto removeImage(String target, String kindValue) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        Target t = resolveTarget(tenantId, target);
        LetterheadImage kind = imageKind(kindValue);
        Letterhead row = lockOrCreate(tenantId, t.branchId());
        if (image(row, kind) != null) {
            setImage(row, kind, null);
            save(row, scope, "letterhead.image_removed", t.label() + ": " + kind.value() + " removed.");
        }
        return reload(tenantId, t);
    }

    // ------------------------------------------------------------- resolution

    /**
     * What a letter for an estate under this branch prints (null: a
     * company-level estate, which uses the company's letterhead), with what's
     * missing and the letterhead versions it rests on. Read under the caller's
     * RLS.
     *
     * @param branchVersion null for a company-level estate; 0 when the branch sets nothing of its own
     */
    public record ResolvedLetterhead(PrintedLetterheadDto printed, List<String> missing, int companyVersion,
                                     Integer branchVersion) {
    }

    @Transactional(readOnly = true)
    public ResolvedLetterhead resolve(UUID tenantId, UUID branchId) {
        CompanyLetterheadDetails details = details(tenantId);
        List<Letterhead> rows = letterheads.findByTenantId(tenantId);
        Letterhead company = rows.stream().filter(r -> r.getBranchId() == null).findFirst().orElse(null);
        Map<UUID, StoredFileInfo> files = files(rows);
        int companyVersion = company == null ? 0 : company.getVersion();
        if (branchId == null) {
            PrintedLetterheadDto printed = printed(details, null, company, null, files);
            return new ResolvedLetterhead(printed, missing(printed), companyVersion, null);
        }
        Letterhead own = rows.stream().filter(r -> branchId.equals(r.getBranchId())).findFirst().orElse(null);
        CompanyLetterheadDetails.BranchOffice office = details.branches().stream()
                .filter(b -> b.id().equals(branchId)).findFirst()
                .orElse(new CompanyLetterheadDetails.BranchOffice(branchId, null, null, null, null));
        PrintedLetterheadDto printed = printed(details, office, own, company, files);
        return new ResolvedLetterhead(printed, missing(printed), companyVersion, own == null ? 0 : own.getVersion());
    }

    /**
     * What a letter prints under one letterhead: the company's identity, the
     * branch's office where it has one, and each image and the signatory from
     * the branch where it sets its own, otherwise the company's.
     */
    private PrintedLetterheadDto printed(CompanyLetterheadDetails details, CompanyLetterheadDetails.BranchOffice office,
                                         Letterhead own, Letterhead company, Map<UUID, StoredFileInfo> files) {
        boolean branch = office != null;
        String address = details.address();
        String phone = details.phone();
        String email = details.email();
        if (branch) {
            address = office.address() != null ? office.address() : address;
            phone = office.phone() != null ? office.phone() : phone;
            email = office.email() != null ? office.email() : email;
        }
        Letterhead signer = branch && own != null && own.hasOwnSignatory() ? own : branch ? company : own;
        String signerSource = signer == null ? null : signer == own ? sourceOf(branch) : COMPANY;
        return new PrintedLetterheadDto(details.registeredName(), details.rcNumber(), address, phone, email,
                details.website(),
                signer == null ? null : signer.getSignatoryName(),
                signer == null ? null : signer.getSignatoryTitle(),
                signerSource,
                inherited(LetterheadImage.LOGO, own, branch ? company : null, branch, files),
                signer == null ? null : imageDto(LetterheadImage.SIGNATURE, signer.getSignatureFileId(), signerSource, files),
                inherited(LetterheadImage.SEAL, own, branch ? company : null, branch, files));
    }

    private static LetterheadImageDto inherited(LetterheadImage kind, Letterhead own, Letterhead fallback,
                                                boolean branch, Map<UUID, StoredFileInfo> files) {
        UUID mine = own == null ? null : image(own, kind);
        if (mine != null) {
            return imageDto(kind, mine, sourceOf(branch), files);
        }
        return fallback == null ? null : imageDto(kind, image(fallback, kind), COMPANY, files);
    }

    private static List<String> missing(PrintedLetterheadDto printed) {
        List<String> missing = new ArrayList<>();
        if (printed.signatoryName() == null) {
            missing.add("signatory_name");
        }
        if (printed.signatoryTitle() == null) {
            missing.add("signatory_title");
        }
        if (printed.signature() == null) {
            missing.add("signature");
        }
        return missing;
    }

    // ------------------------------------------------------------- shared

    private record Target(UUID branchId, String branchName) {
        String label() {
            return branchId == null ? "Company letterhead" : "Letterhead for branch '" + branchName + "'";
        }
    }

    /** {@code company}, or the id of one of the caller's company's branches. */
    private Target resolveTarget(UUID tenantId, String target) {
        LetterTarget t = LetterTarget.resolve(tenancy, tenantId, target);
        return new Target(t.branchId(), t.branchName());
    }

    private Letterhead lockOrCreate(UUID tenantId, UUID branchId) {
        Optional<Letterhead> existing = branchId == null
                ? letterheads.findCompanyForUpdate(tenantId)
                : letterheads.findBranchForUpdate(tenantId, branchId);
        // A new row starts at version 0 and is saved as 1 with its first change.
        return existing.orElseGet(() -> Letterhead.builder().tenantId(tenantId).branchId(branchId).version(0).build());
    }

    private void save(Letterhead row, TenantScope scope, String action, String detail) {
        row.setVersion(row.getVersion() + 1);
        Letterhead saved = letterheads.saveAndFlush(row);
        auditApi.record(AuditEntryRequest.of(scope.userId(), action, "letterhead", saved.getId(),
                saved.getTenantId(), detail));
        log.info("Letterhead changed: {} ({}) by actor {}", saved.getId(), action, scope.userId());
    }

    private LetterheadDto reload(UUID tenantId, Target t) {
        CompanyLetterheadDetails details = details(tenantId);
        List<Letterhead> rows = letterheads.findByTenantId(tenantId);
        Letterhead company = rows.stream().filter(r -> r.getBranchId() == null).findFirst().orElse(null);
        Map<UUID, StoredFileInfo> files = files(rows);
        if (t.branchId() == null) {
            return toDto(details, null, company, null, files);
        }
        Letterhead own = rows.stream().filter(r -> t.branchId().equals(r.getBranchId())).findFirst().orElse(null);
        CompanyLetterheadDetails.BranchOffice office = details.branches().stream()
                .filter(b -> b.id().equals(t.branchId())).findFirst()
                .orElse(new CompanyLetterheadDetails.BranchOffice(t.branchId(), t.branchName(), null, null, null));
        return toDto(details, office, own, company, files);
    }

    private LetterheadDto toDto(CompanyLetterheadDetails details, CompanyLetterheadDetails.BranchOffice office,
                                Letterhead own, Letterhead company, Map<UUID, StoredFileInfo> files) {
        boolean branch = office != null;
        String source = sourceOf(branch);
        PrintedLetterheadDto printed = printed(details, office, own, company, files);
        return new LetterheadDto(
                source,
                branch ? office.id() : null,
                branch ? office.name() : null,
                own == null ? 0 : own.getVersion(),
                own == null ? null : own.getUpdatedAt() != null ? own.getUpdatedAt() : own.getCreatedAt(),
                own == null ? null : own.getSignatoryName(),
                own == null ? null : own.getSignatoryTitle(),
                own == null ? null : imageDto(LetterheadImage.LOGO, own.getLogoFileId(), source, files),
                own == null ? null : imageDto(LetterheadImage.SIGNATURE, own.getSignatureFileId(), source, files),
                own == null ? null : imageDto(LetterheadImage.SEAL, own.getSealFileId(), source, files),
                printed,
                missing(printed));
    }

    private static LetterheadImageDto imageDto(LetterheadImage kind, UUID fileId, String source,
                                               Map<UUID, StoredFileInfo> files) {
        if (fileId == null) {
            return null;
        }
        StoredFileInfo file = files.get(fileId);
        return new LetterheadImageDto(kind.value(), fileId, file == null ? null : file.fileName(),
                file == null || file.fileType() == null ? null : file.fileType().getValue(),
                file == null ? 0 : file.sizeBytes(), source);
    }

    private Map<UUID, StoredFileInfo> files(Collection<Letterhead> rows) {
        List<UUID> ids = rows.stream().flatMap(LetterheadService::fileIds).distinct().toList();
        return ids.isEmpty() ? Map.of() : storage.findAll(ids).stream()
                .collect(Collectors.toMap(StoredFileInfo::id, Function.identity()));
    }

    private static Stream<UUID> fileIds(Letterhead row) {
        return Stream.of(row.getLogoFileId(), row.getSignatureFileId(), row.getSealFileId()).filter(Objects::nonNull);
    }

    private static UUID image(Letterhead row, LetterheadImage kind) {
        return switch (kind) {
            case LOGO -> row.getLogoFileId();
            case SIGNATURE -> row.getSignatureFileId();
            case SEAL -> row.getSealFileId();
        };
    }

    private static void setImage(Letterhead row, LetterheadImage kind, UUID fileId) {
        switch (kind) {
            case LOGO -> row.setLogoFileId(fileId);
            case SIGNATURE -> row.setSignatureFileId(fileId);
            case SEAL -> row.setSealFileId(fileId);
        }
    }

    private static LetterheadImage imageKind(String value) {
        return LetterheadImage.fromValue(value).orElseThrow(() -> new DocumentException.RequestRejected(
                "UNKNOWN_IMAGE_KIND", "The image must be a 'logo', 'signature' or 'seal'."));
    }

    private static String describeSignatory(Letterhead row) {
        return row.getSignatoryName() == null && row.getSignatoryTitle() == null ? "(none)"
                : Objects.toString(row.getSignatoryName(), "?") + ", " + Objects.toString(row.getSignatoryTitle(), "?");
    }

    private static String sourceOf(boolean branch) {
        return branch ? BRANCH : COMPANY;
    }

    private CompanyLetterheadDetails details(UUID tenantId) {
        return tenancy.letterheadDetails(tenantId).orElseThrow(DocumentException.CompanyScopeRequired::new);
    }
}
