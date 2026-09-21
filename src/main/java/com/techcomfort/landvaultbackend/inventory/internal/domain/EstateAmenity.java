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
 * One amenity on an estate ("Paved roads", "24/7 security").
 * <p>
 * A child table rather than a delimited string on {@code estates}: a
 * comma-joined column cannot be filtered on, indexed, or corrected without
 * rewriting the whole value, and breaks the first time an amenity name
 * contains the delimiter.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "estate_amenities",
        indexes = @Index(name = "idx_estate_amenities_estate_id", columnList = "estate_id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uq_estate_amenities_estate_name", columnNames = {"estate_id", "name"})
)
@SQLRestriction("deleted = false")
public class EstateAmenity extends AbstractEntity {

    @Column(name = "estate_id", nullable = false)
    private UUID estateId;

    @Column(name = "name", nullable = false)
    private String name;
}
