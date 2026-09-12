package com.techcomfort.landvaultbackend.identity.entity;

import com.techcomfort.landvaultbackend.common.AbstractEntity;
import com.techcomfort.landvaultbackend.identity.constants.UserStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Currency;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "users")
public class User extends AbstractEntity {


    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    /** Display preference only. Never used to convert stored amounts. */
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

    @Column(name = "two_fa_enabled", nullable = false)
    private Boolean twoFaEnabled;

    @Column(name = "two_fa_secret")
    private String twoFaSecret;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Override
    public void prePersist() {
        super.prePersist();
        if (twoFaEnabled == null) {
            twoFaEnabled = false;
        }
        if (status == null) {
            status = UserStatus.PENDING_VERIFICATION;
        }
        if (email != null) {
            email = email.trim().toLowerCase();
        }
    }
}
