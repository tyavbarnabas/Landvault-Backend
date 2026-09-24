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

/** A row of {@code marketplace_default_terms} — the current terms, keyed by estate. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "marketplace_default_terms")
public class DefaultTermsView {

    @Id
    @Column(name = "estate_id")
    private UUID estateId;

    @Column(name = "default_terms_id")
    private UUID defaultTermsId;

    @Column(name = "revocation_trigger")
    private String revocationTrigger;

    @Column(name = "revocation_notice_days")
    private Integer revocationNoticeDays;

    @Column(name = "on_revocation_refund")
    private String onRevocationRefund;

    @Column(name = "development_deadline_months")
    private Integer developmentDeadlineMonths;

    @Column(name = "transfer_requires_consent")
    private boolean transferRequiresConsent;

    @Column(name = "notes")
    private String notes;
}
