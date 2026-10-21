package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.NigerianStates;
import com.techcomfort.landvaultbackend.common.PostalAddress;
import com.techcomfort.landvaultbackend.identity.dto.AddressResponse;
import com.techcomfort.landvaultbackend.identity.dto.SaveAddressRequest;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AddressException;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A buyer's own postal address — entered once, edited in Settings, copied
 * onto each purchase by checkout. Personal data treated like the phone: never
 * logged, and the audit entry says only that it changed. See AGENTS.md,
 * "A buyer's address".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

    private static final Set<String> COUNTRY_CODES = Set.of(Locale.getISOCountries());

    private final UserRepository userRepository;
    private final NigerianStates nigerianStates;
    private final AuditApi auditApi;

    /** Empty when the account has none yet. */
    @Transactional(readOnly = true)
    public Optional<AddressResponse> get(UUID userId) {
        User user = load(userId);
        return user.address().map(a -> AddressResponse.of(a, user.getAddressUpdatedAt()));
    }

    /** Replaces the whole address. The same address again changes nothing and records nothing. */
    @Transactional
    public AddressResponse save(UUID userId, SaveAddressRequest request) {
        User user = load(userId);
        PostalAddress address = normalise(request);
        if (user.address().filter(address::equals).isPresent()) {
            return AddressResponse.of(address, user.getAddressUpdatedAt());
        }

        boolean first = user.address().isEmpty();
        Instant now = Instant.now();
        user.replaceAddress(address, now);
        userRepository.save(user);

        auditApi.record(AuditEntryRequest.of(userId, "user.address_updated", "user", userId, user.getTenantId(),
                first ? "Address added." : "Address changed."));
        log.info("Address {} for user {}", first ? "added" : "changed", userId);
        return AddressResponse.of(address, now);
    }

    private PostalAddress normalise(SaveAddressRequest request) {
        String country = request.country().trim().toUpperCase(Locale.ROOT);
        if (!COUNTRY_CODES.contains(country)) {
            throw new AddressException.UnknownCountry(request.country().trim());
        }
        String state = request.state().trim();
        if ("NG".equals(country)) {
            state = nigerianStates.resolve(state)
                    .map(NigerianStates.State::name)
                    .orElseThrow(() -> new AddressException.UnknownState(request.state().trim()));
        }
        return new PostalAddress(request.line1().trim(), blankToNull(request.line2()), request.city().trim(),
                state, blankToNull(request.postalCode()), country);
    }

    private User load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
