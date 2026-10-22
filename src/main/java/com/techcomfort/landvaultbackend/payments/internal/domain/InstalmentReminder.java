package com.techcomfort.landvaultbackend.payments.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import com.techcomfort.landvaultbackend.payments.internal.enums.ReminderStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/** An instalment reminder that was sent (changeset 104): one per instalment and stage, never changed. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "instalment_reminders")
public class InstalmentReminder extends AbstractAppendOnlyEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "schedule_version", nullable = false, updatable = false)
    private Integer scheduleVersion;

    @Column(name = "instalment_sequence", nullable = false, updatable = false)
    private Integer instalmentSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, updatable = false, length = 32)
    private ReminderStage stage;
}
