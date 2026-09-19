package com.techcomfort.landvaultbackend.identity.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.controllers.AuthController;
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
@RestControllerAdvice(assignableTypes = AuthController.class)
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
