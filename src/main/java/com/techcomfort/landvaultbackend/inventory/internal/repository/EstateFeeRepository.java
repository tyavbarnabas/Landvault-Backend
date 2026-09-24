package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateFee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Carries no tenant predicate, deliberately: changeset 058's policies are
 * what isolate these rows, and a second, weaker copy of that guarantee in
 * application code is how the two eventually disagree.
 */
public interface EstateFeeRepository extends JpaRepository<EstateFee, UUID> {

    List<EstateFee> findByEstateIdAndVersionOrderByFeeTypeAsc(UUID estateId, Integer version);
}
