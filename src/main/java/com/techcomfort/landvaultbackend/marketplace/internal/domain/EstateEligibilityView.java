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

/**
 * A row of {@code marketplace_estate_eligibility}. Internal: read by the
 * publish check, never serialized to a buyer. Views are read-only
 * ({@code @Immutable}); see changeset 049 for what they bypass and why.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_estate_eligibility")
public class EstateEligibilityView {

    @Id
    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "published")
    private boolean published;

    @Column(name = "tenant_verified")
    private boolean tenantVerified;

    @Column(name = "tenant_entitled")
    private boolean tenantEntitled;

    @Column(name = "tenant_active")
    private boolean tenantActive;

    @Column(name = "eligible")
    private boolean eligible;
}
