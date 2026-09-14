package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.tenancy.internal.GatewayName;
import com.techcomfort.landvaultbackend.tenancy.internal.GatewayStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A payment gateway an {@link Organization} has connected for settlement —
 * a child table, not a JSON blob, because "which tenants have Paystack
 * connected" and gateway-coverage reporting both need to query this.
 * <p>
 * <b>No credentials live here.</b> API keys and secrets belong in a secrets
 * manager, never in the application database — "just one encrypted column"
 * is exactly how credentials end up in a database backup. This table only
 * ever records connection status.
 * <p>
 * <b>Stripe is not a Nigerian local rail.</b> Local collection runs through
 * {@link GatewayName}'s enumerated providers; diaspora payments use virtual
 * accounts or international wire instead. Do not add Stripe to that enum.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "organization_gateways",
        indexes = {
                @Index(name = "idx_organization_gateways_organization_id", columnList = "organization_id")
        }
)
@SQLRestriction("deleted = false")
public class OrganizationGateway extends AbstractEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_name", nullable = false, length = 32)
    private GatewayName gatewayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private GatewayStatus status;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Override
    public void prePersist() {
        super.prePersist();
        if (getTenantId() == null) {
            setTenantId(organizationId);
        }
    }
}
