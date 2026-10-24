package com.techcomfort.landvaultbackend.payments.internal.repository;

import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRestructureInstalment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanRestructureInstalmentRepository extends JpaRepository<PlanRestructureInstalment, UUID> {

    List<PlanRestructureInstalment> findByRestructureIdOrderBySequenceAsc(UUID restructureId);
}
