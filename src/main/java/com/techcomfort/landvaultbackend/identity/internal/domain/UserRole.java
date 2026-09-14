package com.techcomfort.landvaultbackend.identity.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A role held by a user — not a plain join row. {@code scopedBranchId} null
 * means organization-/platform-wide; set means confined to that branch. See
 * AGENTS.md for the hard-wall-vs-switchable-lens distinction this carries.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "user_roles",
        indexes = {
                @Index(name = "idx_user_roles_user_id", columnList = "user_id"),
                @Index(name = "idx_user_roles_role_id", columnList = "role_id"),
                @Index(name = "idx_user_roles_scoped_branch_id", columnList = "scoped_branch_id"),
                @Index(name = "idx_user_roles_granted_by_user_id", columnList = "granted_by_user_id")
        }
)
@SQLRestriction("deleted = false")
public class UserRole extends AbstractEntity {

    // Real association (not a plain UUID) so User.roles can be a lazy
    // @OneToMany — User and UserRole are both in the identity module, so
    // this doesn't cross a Modulith module boundary.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    // Null = organization-/platform-wide (a hard wall for some roles, a
    // switchable lens for others — see AGENTS.md). Set = confined to that
    // branch. Cross-module (tenancy.Branch), so a plain UUID, not a
    // @ManyToOne.
    @Column(name = "scoped_branch_id")
    private UUID scopedBranchId;

    @Column(name = "granted_by_user_id")
    private UUID grantedByUserId;
}
