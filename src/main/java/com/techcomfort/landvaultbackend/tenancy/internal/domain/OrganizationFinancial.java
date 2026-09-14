package com.techcomfort.landvaultbackend.tenancy.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
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

import java.util.UUID;

/**
 * Where an {@link Organization}'s settlement money goes (1:1). accountName
 * mismatch vs. registeredName is a reviewer signal only — see AGENTS.md.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "organization_financial",
        indexes = {
                @Index(name = "idx_organization_financial_organization_id", columnList = "organization_id")
        }
)
@SQLRestriction("deleted = false")
public class OrganizationFinancial extends AbstractEntity {

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    // NUBAN: exactly 10 digits. varchar, not numeric — see class Javadoc.
    @Column(name = "account_number", nullable = false, length = 10)
    private String accountNumber;

    // Compared against Organization.registeredName as a reviewer signal
    // only — see class Javadoc. Never validated or blocked here.
    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_currency", nullable = false, length = 3)
    private Currency settlementCurrency;

    @Override
    public void prePersist() {
        super.prePersist();
        if (getTenantId() == null) {
            setTenantId(organizationId);
        }
    }
}
