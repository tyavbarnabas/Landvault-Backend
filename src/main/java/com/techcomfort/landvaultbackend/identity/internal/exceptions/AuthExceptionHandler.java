package com.techcomfort.landvaultbackend.identity.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.controllers.AuthController;
import com.techcomfort.landvaultbackend.identity.internal.controllers.TwoFactorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns AuthController's exceptions into AGENTS.md's {message, code, fieldErrors} error body. */
@RestControllerAdvice(assignableTypes = {AuthController.class, TwoFactorController.class})
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.EmailAlreadyRegistered.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("An account with this email already exists.", "EMAIL_ALREADY_REGISTERED"));
    }

    @ExceptionHandler(AuthException.InvalidCredentials.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("Invalid email or password.", "INVALID_CREDENTIALS"));
    }

    @ExceptionHandler(AuthException.AccountNotActive.class)
    public ResponseEntity<ErrorResponse> handleAccountNotActive(AuthException.AccountNotActive ex) {
        boolean suspended = ex.status() == UserStatus.SUSPENDED;
        String message = suspended
                ? "This account has been suspended. Contact support for help."
                : "This account has been deactivated. Contact support for help.";
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(message, suspended ? "ACCOUNT_SUSPENDED" : "ACCOUNT_DEACTIVATED"));
    }

    @ExceptionHandler(AuthException.TenantNotActive.class)
    public ResponseEntity<ErrorResponse> handleTenantNotActive() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("Your organization's account is not active. Contact your administrator.", "TENANT_NOT_ACTIVE"));
    }

    @ExceptionHandler(AuthException.InvalidRefreshToken.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("Refresh token is invalid or expired.", "INVALID_REFRESH_TOKEN"));
    }

    // 400, not 401: this isn't a failed authentication of a session, it's a
    // bad input to a public endpoint. One message for every failure mode —
    // see AuthException.InvalidOrExpiredResetCode.
    @ExceptionHandler(AuthException.InvalidOrExpiredResetCode.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrExpiredResetCode() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("That reset code is invalid or has expired. Request a new one.", "INVALID_OR_EXPIRED_CODE"));
    }

    // One message for every rejected second factor — see the exception.
    @ExceptionHandler(AuthException.InvalidTwoFactorCode.class)
    public ResponseEntity<ErrorResponse> handleInvalidTwoFactorCode() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("That code is not valid. Try again, or use a recovery code.", "INVALID_TWO_FACTOR_CODE"));
    }

    @ExceptionHandler(AuthException.InvalidOrExpiredChallenge.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrExpiredChallenge() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("This sign-in attempt is no longer valid. Start again.", "INVALID_TWO_FACTOR_CHALLENGE"));
    }

    @ExceptionHandler(AuthException.TwoFactorLockedOut.class)
    public ResponseEntity<ErrorResponse> handleTwoFactorLockedOut() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of("Too many incorrect codes. Try again later.", "TWO_FACTOR_LOCKED_OUT"));
    }

    @ExceptionHandler(AuthException.TwoFactorSetupRequired.class)
    public ResponseEntity<ErrorResponse> handleTwoFactorSetupRequired() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("Start two-factor setup before confirming it.", "TWO_FACTOR_SETUP_REQUIRED"));
    }

    @ExceptionHandler(AuthException.TwoFactorNotEnabled.class)
    public ResponseEntity<ErrorResponse> handleTwoFactorNotEnabled() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("Two-factor authentication is not enabled on this account.", "TWO_FACTOR_NOT_ENABLED"));
    }

    @ExceptionHandler(AuthException.TwoFactorMandatory.class)
    public ResponseEntity<ErrorResponse> handleTwoFactorMandatory() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(
                        "Two-factor authentication is required for platform staff accounts and cannot be turned off.",
                        "TWO_FACTOR_MANDATORY"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(ErrorResponse.fieldErrors(fieldErrors));
    }

    // Covers RegisterRequest's ignoreUnknown=false rejection, among other malformed-body cases.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Request body is malformed or contains unexpected fields.", "MALFORMED_REQUEST"));
    }
}
