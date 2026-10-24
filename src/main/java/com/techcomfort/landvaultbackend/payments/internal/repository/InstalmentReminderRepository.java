package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.InstalmentReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InstalmentReminderRepository extends JpaRepository<InstalmentReminder, UUID> {

    /**
     * Records a reminder unless it was already sent; 1 means this caller is
     * the one to send it. One statement, so two job runs at once can't both
     * claim it.
     */
    @Modifying
    @Query(value = "INSERT INTO instalment_reminders (id, created_at, transaction_id, schedule_version, "
            + "instalment_sequence, stage) VALUES (gen_random_uuid(), now(), :transactionId, :version, :sequence, :stage) "
            + "ON CONFLICT (transaction_id, schedule_version, instalment_sequence, stage) DO NOTHING",
            nativeQuery = true)
    int claim(@Param("transactionId") UUID transactionId, @Param("version") int scheduleVersion,
              @Param("sequence") int sequence, @Param("stage") String stage);

    List<InstalmentReminder> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
