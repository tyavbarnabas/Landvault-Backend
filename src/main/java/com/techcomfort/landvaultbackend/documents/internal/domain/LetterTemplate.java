package com.techcomfort.landvaultbackend.documents.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** One version of a company's (branch null) or branch's allocation-letter wording (changeset 094). Never updated. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "letter_templates")
public class LetterTemplate extends AbstractAppendOnlyEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", updatable = false)
    private UUID branchId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "opening", nullable = false, updatable = false)
    private String opening;

    @Column(name = "closing", updatable = false)
    private String closing;

    @Column(name = "sign_off", nullable = false, updatable = false, length = 80)
    private String signOff;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "standard_clauses", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] standardClauses;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;
}
