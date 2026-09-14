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
 * Where an {@link Organization}'s settlement money goes — a 1:1 record
 * (enforced by a unique constraint on {@code organization_id}).
 * <p>
 * {@code accountNumber} is a NUBAN: exactly 10 digits, stored as
 * {@code varchar(10)} rather than a numeric type. Leading zeros are
 * significant and an account number is an identifier, never a quantity.
 * <p>
 * <b>The account-name mismatch rule is a comment, not a constraint.</b> When
 * {@code accountName} doesn't closely match the organization's
 * {@code registeredName}, that's a compliance red flag worth a reviewer's
 * attention — but not a blocker. The frontend models it the same way
 * ({@code accountNameLooksMismatched} produces a warning banner, never a
 * validation failure): legitimate mismatches exist — trading names,
 * abbreviations, recently renamed companies. No constraint or trigger
 * enforces this here; the comparison is a reviewer signal, computed in the
 * verification service in a later slice.
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
