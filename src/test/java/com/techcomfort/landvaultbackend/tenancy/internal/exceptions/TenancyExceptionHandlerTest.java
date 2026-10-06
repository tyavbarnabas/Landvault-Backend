package com.techcomfort.landvaultbackend.tenancy.internal.exceptions;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** A uniqueness clash is labelled by the constraint that fired, never assumed to be the RC number. */
class TenancyExceptionHandlerTest {

    private final TenancyExceptionHandler handler = new TenancyExceptionHandler();

    @Test
    void eachConstraintGetsItsOwnCodeAndAnUnknownOneIsNotCalledAnRcDuplicate() {
        assertThat(codeFor("uq_organizations_rc_number")).isEqualTo("RC_NUMBER_ALREADY_REGISTERED");
        assertThat(codeFor("uq_staff_invitations_open_email")).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(codeFor("idx_users_email_lower")).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(codeFor("uq_something_new")).isEqualTo("DUPLICATE_RECORD");
        assertThat(handler.handleDataIntegrityViolation(new DataIntegrityViolationException("no cause"))
                .getBody().code()).isEqualTo("DUPLICATE_RECORD");
    }

    private String codeFor(String constraint) {
        var cause = new ConstraintViolationException("duplicate", new SQLException("duplicate"), constraint);
        return handler.handleDataIntegrityViolation(new DataIntegrityViolationException("clash", cause)).getBody().code();
    }
}
