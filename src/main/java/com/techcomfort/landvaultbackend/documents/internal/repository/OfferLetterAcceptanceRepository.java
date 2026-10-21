package com.techcomfort.landvaultbackend.documents.internal.repository;

import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetterAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferLetterAcceptanceRepository extends JpaRepository<OfferLetterAcceptance, UUID> {

    Optional<OfferLetterAcceptance> findByOfferLetterId(UUID offerLetterId);

    List<OfferLetterAcceptance> findByOfferLetterIdIn(Collection<UUID> offerLetterIds);

    /** Held to the end of the transaction, so two accepts of one offer take turns. */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('offer_letter'), "
            + "hashtext(CAST(:offer AS text)))) AS locked", nativeQuery = true)
    Integer lockOffer(@Param("offer") UUID offerLetterId);
}
