package com.techcomfort.landvaultbackend.identity.internal.repository;

import com.techcomfort.landvaultbackend.identity.internal.domain.TwoFaChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TwoFaChallengeRepository extends JpaRepository<TwoFaChallenge, UUID> {

    // Looked up by hash, never by raw value — same rule as refresh tokens.
    Optional<TwoFaChallenge> findByTokenHash(String tokenHash);
}
