package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.internal.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.domain.UserRole;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the platform's first Super Admin account — the only way one can
 * ever come to exist, since {@code POST /api/auth/register} deliberately
 * cannot create anything but a buyer. See AGENTS.md for why this is a
 * deliberate, environment-gated deployment step rather than a Liquibase
 * seed (a seeded password is a password in git, identical across every
 * environment including production).
 * <p>
 * Runs once, after Liquibase (an {@link ApplicationRunner} fires after the
 * context — and everything {@code InitializingBean}, which is how
 * Liquibase's own migration runs, completes — is fully refreshed), and does
 * nothing at all unless explicitly enabled. See {@link SuperAdminBootstrapProperties}.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SuperAdminBootstrapProperties.class)
public class SuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);
    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";

    private final SuperAdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            // The overwhelmingly common case — an ordinary startup does
            // nothing at all: no log line, no query, no risk.
            return;
        }

        Role superAdminRole = roleRepository.findByCode(SUPER_ADMIN_ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Seeded '" + SUPER_ADMIN_ROLE_CODE + "' role is missing — Liquibase migrations "
                                + "did not run. Refusing to create one here rather than mask the real problem."));

        if (userRoleRepository.existsByRoleId(superAdminRole.getId())) {
            // A misconfigured restart (the env var left set after the
            // first successful run) must not mint a second admin, and
            // whoever can set an env var on a running system must not be
            // able to grant themselves one this way either.
            log.info("Super Admin bootstrap skipped: an account already holds the super_admin role.");
            return;
        }

        if (isBlank(properties.email()) || isBlank(properties.password())) {
            // A half-configured bootstrap that silently does nothing is
            // worse than a crash — you'd discover the gap when you try to
            // log in, with no clue why. Fail loudly instead.
            throw new IllegalStateException(
                    "landvault.bootstrap.super-admin is enabled but email/password is blank. Set "
                            + "BOOTSTRAP_SUPER_ADMIN_EMAIL and BOOTSTRAP_SUPER_ADMIN_PASSWORD, or unset "
                            + "BOOTSTRAP_SUPER_ADMIN.");
        }

        User user = User.builder()
                // Not configurable beyond first/last name — country/currency
                // are NOT NULL columns shared by every kind of account, but
                // carry no real meaning for platform staff (no diaspora/KYC
                // concept applies to a Super Admin). NG/NGN satisfies the
                // schema without fabricating anything a screen would show
                // as if it meant something.
                .firstName(isBlank(properties.firstName()) ? "Super" : properties.firstName())
                .lastName(isBlank(properties.lastName()) ? "Admin" : properties.lastName())
                .email(properties.email())
                .passwordHash(passwordEncoder.encode(properties.password()))
                .country("NG")
                .currency(Currency.NGN)
                .status(UserStatus.ACTIVE) // not PENDING_VERIFICATION — no OTP flow yet, operator verifies out of band
                .twoFaEnabled(false)
                .mustChangePassword(true) // see AGENTS.md — not yet enforced at login, the endpoint doesn't exist
                .build();
        user = userRepository.save(user);

        UserRole assignment = UserRole.builder()
                .user(user)
                .roleId(superAdminRole.getId())
                .scopedBranchId(null) // platform-wide — see AGENTS.md's nullability rules
                .build();
        userRoleRepository.save(assignment);

        // The email, never the password — at any level, including debug.
        log.info("Super Admin bootstrap created account: {}", user.getEmail());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
