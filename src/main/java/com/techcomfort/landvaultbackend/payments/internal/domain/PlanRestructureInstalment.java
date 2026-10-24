package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Phase 5c: one new instalment of a restructure proposal (changeset 108). Never changed. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "plan_restructure_instalments")
public class PlanRestructureInstalment extends AbstractAppendOnlyEntity {

    @Column(name = "restructure_id", nullable = false, updatable = false)
    private UUID restructureId;

    @Column(name = "sequence", nullable = false, updatable = false)
    private Integer sequence;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "carried_penalty", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal carriedPenalty;
}
