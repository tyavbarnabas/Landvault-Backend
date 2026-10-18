package com.techcomfort.landvaultbackend.documents.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * The company's letterhead ({@code branchId} null) or one branch's own, holding
 * only what it sets itself (changeset 090). See AGENTS.md, "Documents: letterheads".
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "letterheads")
@SQLRestriction("deleted = false")
public class Letterhead extends AbstractEntity {

    @Column(name = "logo_file_id")
    private UUID logoFileId;

    @Column(name = "signatory_name", length = 120)
    private String signatoryName;

    @Column(name = "signatory_title", length = 120)
    private String signatoryTitle;

    @Column(name = "signature_file_id")
    private UUID signatureFileId;

    @Column(name = "seal_file_id")
    private UUID sealFileId;

    /** Goes up on every change; an approval records the version it saw. */
    @Column(name = "version", nullable = false)
    private int version;

    /** True when this letterhead names its own signer — then it must carry their signature too. */
    public boolean hasOwnSignatory() {
        return signatoryName != null || signatoryTitle != null || signatureFileId != null;
    }
}
