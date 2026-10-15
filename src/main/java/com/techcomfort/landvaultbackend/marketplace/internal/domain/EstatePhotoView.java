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

/** A row of {@code marketplace_estate_photos} (changeset 086). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_estate_photos")
public class EstatePhotoView {

    @Id
    @Column(name = "photo_id")
    private UUID photoId;

    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "caption")
    private String caption;

    @Column(name = "position")
    private int position;

    @Column(name = "cover")
    private boolean cover;
}
