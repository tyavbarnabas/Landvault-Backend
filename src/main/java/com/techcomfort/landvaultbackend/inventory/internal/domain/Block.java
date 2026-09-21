package com.techcomfort.landvaultbackend.inventory.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A subdivision within an estate — Block A, Block C.
 * <p>
 * Deliberately thin. Blocks exist because plots are addressed as "Block C,
 * Plot 4", not because they carry data of their own; resist the urge to hang
 * pricing or status here, both of which belong to the plot or its tier.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "blocks",
        indexes = {
                @Index(name = "idx_blocks_estate_id", columnList = "estate_id"),
                @Index(name = "idx_blocks_tenant_id", columnList = "tenant_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uq_blocks_estate_name", columnNames = {"estate_id", "name"})
)
@SQLRestriction("deleted = false")
public class Block extends AbstractEntity {

    @Column(name = "estate_id", nullable = false)
    private UUID estateId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** Display form when it differs from {@code name} — "Block C" vs "C". */
    @Column(name = "label")
    private String label;
}
