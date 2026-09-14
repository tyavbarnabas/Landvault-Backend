package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * An organizational unit under an {@link Organization} — Heritage, Double
 * King, Premium under Estintin Group; there's no separate "unit" concept.
 * organizationId is the real relationship (tenantId/branchId stay null).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "branches",
        indexes = {
                @Index(name = "idx_branches_organization_id", columnList = "organization_id"),
                @Index(name = "idx_branches_manager_user_id", columnList = "manager_user_id"),
                @Index(name = "idx_branches_parent_branch_id", columnList = "parent_branch_id")
        }
)
@SQLRestriction("deleted = false")
public class Branch extends AbstractEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    // Nullable: a branch can exist before its manager is invited. A plain
    // UUID, not a @ManyToOne to identity's User — that entity is internal
    // to a different Modulith module and must stay unreachable from here.
    @Column(name = "manager_user_id")
    private UUID managerUserId;

    // Nullable self-reference reserving the shape for a future region
    // grouping several branches. No hierarchy logic yet — see class Javadoc.
    @Column(name = "parent_branch_id")
    private UUID parentBranchId;
}
