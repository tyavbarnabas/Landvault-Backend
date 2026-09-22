package com.techcomfort.landvaultbackend.marketplace.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/** A row of {@code marketplace_amenities}. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_amenities")
public class AmenityView {

    @Id
    @Column(name = "amenity_id")
    private UUID amenityId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "name")
    private String name;
}
