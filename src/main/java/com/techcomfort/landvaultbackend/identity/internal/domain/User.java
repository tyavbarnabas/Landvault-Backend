package com.techcomfort.landvaultbackend.identity.internal.domain;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.security.TwoFaSecretConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.List;

/**
 * An account, shared by every kind of platform participant (buyer, tenant
 * staff, platform staff, independent agent) — see AGENTS.md for the
 * tenant/branch nullability rules that distinguish them.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_users_branch_id", columnList = "branch_id"),
                @Index(name = "idx_users_status", columnList = "status")
        }
)
@SQLRestriction("deleted = false")
public class User extends AbstractEntity {

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "gender")
    private String gender;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    // Stores a BCrypt hash, never plaintext — named passwordHash (not
    // password) so a plaintext value written here reads as an obvious bug.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    // True only once the pairing has been CONFIRMED — never set at setup
    // time. See twoFaConfirmedAt and AGENTS.md.
    @Column(name = "two_fa_enabled", nullable = false)
    private Boolean twoFaEnabled;

    // Encrypted at rest by TwoFaSecretConverter — a readable secret here
    // would let anyone with database access mint valid codes for this
    // account. Never logged, never returned in any response.
    @Convert(converter = TwoFaSecretConverter.class)
    @Column(name = "two_fa_secret")
    private String twoFaSecret;

    /**
     * Set only when the user proved their authenticator app holds the
     * secret. Distinct from {@link #twoFaEnabled} on purpose: a secret can
     * exist unconfirmed (setup started, pairing unproven), and enabling 2FA
     * in that state would lock the user out permanently, since recovery
     * codes are only issued at confirmation. See AGENTS.md.
     */
    @Column(name = "two_fa_confirmed_at")
    private Instant twoFaConfirmedAt;

    // Throttling for 2FA verification. Unlike a password-reset code, a TOTP
    // code is continuously valid, so the endpoint is a standing target.
    @Column(name = "two_fa_failed_attempts", nullable = false)
    private Integer twoFaFailedAttempts;

    @Column(name = "two_fa_locked_until")
    private Instant twoFaLockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // True only for SuperAdminBootstrap's own account today — a bootstrap
    // password has passed through an environment variable, probably a
    // shell history, and possibly a deployment log. NOT enforced at login
    // yet (no change-password endpoint exists) — see AGENTS.md.
    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword;

    // Lazy: roles are needed at login, not on every read. Note: this user's
    // *permissions* are never stored anywhere on User — they're assembled
    // at login by flattening roles -> role_permissions -> permissions. See
    // AGENTS.md.
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<UserRole> roles;

    @Override
    public void prePersist() {
        super.prePersist();
        if (twoFaEnabled == null) {
            twoFaEnabled = false;
        }
        if (twoFaFailedAttempts == null) {
            twoFaFailedAttempts = 0;
        }
        if (mustChangePassword == null) {
            mustChangePassword = false;
        }
        if (status == null) {
            status = UserStatus.PENDING_VERIFICATION;
        }
        if (email != null) {
            email = email.trim().toLowerCase();
        }
    }
}
