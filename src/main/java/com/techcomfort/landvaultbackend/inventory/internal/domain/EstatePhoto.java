package com.techcomfort.landvaultbackend.inventory.internal.domain;

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

/** One photo of an estate, pointing at a stored file (changeset 086). See AGENTS.md, "Estate photos". */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "estate_photos")
@SQLRestriction("deleted = false")
public class EstatePhoto extends AbstractEntity {

    @Column(name = "estate_id", nullable = false, updatable = false)
    private UUID estateId;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(name = "caption", length = 200)
    private String caption;

    /** From 0, contiguous; kept so by the service. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "cover", nullable = false)
    private boolean cover;
}
