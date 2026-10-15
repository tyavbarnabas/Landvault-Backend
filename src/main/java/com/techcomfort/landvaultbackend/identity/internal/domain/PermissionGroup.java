package com.techcomfort.landvaultbackend.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A heading in the portal role editor ("Estates"), in display order. Reference data, changeset 085. */
@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "permission_groups")
public class PermissionGroup {

    @Id
    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
