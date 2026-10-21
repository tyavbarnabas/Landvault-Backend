package com.techcomfort.landvaultbackend.documents.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractAppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/** A buyer accepting an offer letter (changeset 098): one per letter, never changed. */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "offer_letter_acceptances")
public class OfferLetterAcceptance extends AbstractAppendOnlyEntity {

    @Column(name = "offer_letter_id", nullable = false, updatable = false)
    private UUID offerLetterId;

    /** From the session, never the request. */
    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;
}
