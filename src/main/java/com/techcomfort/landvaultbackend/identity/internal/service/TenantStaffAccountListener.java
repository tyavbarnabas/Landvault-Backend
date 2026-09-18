package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DuplicateEmailException;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.domain.UserRole;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.tenancy.TenantStaffAccountRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates a tenant staff account in response to {@link TenantStaffAccountRequested}
 * — see that event's Javadoc for why this is event-driven rather than a
 * direct cross-module call. {@code @Transactional(propagation = MANDATORY)}:
 * this must never run outside the publisher's own transaction — if there
 * is no active transaction when this fires, that's a bug in the caller,
 * not something to silently start a new transaction for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantStaffAccountListener {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTenantStaffAccountRequested(TenantStaffAccountRequested event) {
        Role role = roleRepository.findByCode(event.roleCode())
                .orElseThrow(() -> new IllegalStateException(
                        "Seeded '" + event.roleCode() + "' role is missing — migrations did not run."));

        // Proactive check for the overwhelmingly common case (a clean,
        // correctly-labelled conflict rather than a raw constraint
        // violation) — see DuplicateEmailException. idx_users_email_lower
        // is the real, DB-level backstop for the concurrent-create race
        // this check alone can't close; caught below.
        if (userRepository.existsByEmailIgnoreCase(event.email())) {
            throw new DuplicateEmailException();
        }

        User user = User.builder()
                .firstName(event.firstName())
                .lastName(event.lastName())
                .email(event.email())
                .phone(event.phone())
                // Not NIN/passport-verified, not a buyer — country/currency
                // are NOT NULL columns every account carries regardless of
                // kind, but carry no real meaning for tenant staff. Same
                // NG/NGN default as SuperAdminBootstrap, for the same reason.
                .country("NG")
                .currency(Currency.NGN)
                .status(UserStatus.PENDING_VERIFICATION)
                .twoFaEnabled(false)
                // See TODO below — must change this generated password
                // before it's usable for anything real, once that endpoint
                // exists.
                .mustChangePassword(true)
                .passwordHash(passwordEncoder.encode(generateTemporaryPassword()))
                .build();
        // tenantId is set explicitly, not via the builder — this is the
        // one legitimate exception to "tenant staff never set their own
        // tenant" (AuthService.register's rule): the PLATFORM is assigning
        // it here, via a Super-Admin-only endpoint, not the account owner.
        user.setTenantId(event.tenantId());
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent write to the same email —
            // the proactive check above missed it.
            throw new DuplicateEmailException();
        }

        UserRole assignment = UserRole.builder()
                .user(user)
                .roleId(role.getId())
                .scopedBranchId(null) // organization-wide — matches an Executive Director's real authority
                .build();
        userRoleRepository.save(assignment);

        // The email only — never the password, at any level, including
        // debug, same rule as SuperAdminBootstrap.
        log.info("Created '{}' account for tenant {}: {}", event.roleCode(), event.tenantId(), user.getEmail());

        // TODO: this account should receive an invitation email with a
        // set-password link once the invitation flow exists (a later
        // slice). The generated password is discarded the moment this
        // method returns — never logged, never returned to any caller,
        // including the Super Admin who triggered this.
    }

    private static String generateTemporaryPassword() {
        byte[] randomBytes = new byte[18];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
