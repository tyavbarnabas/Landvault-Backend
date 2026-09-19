package com.techcomfort.landvaultbackend.tenancy.internal.exceptions;

import com.techcomfort.landvaultbackend.common.DuplicateEmailException;
import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.tenancy.internal.controllers.AdminTenantController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns AdminTenantController's write-endpoint exceptions into AGENTS.md's {message, code, fieldErrors} error body. */
@RestControllerAdvice(assignableTypes = AdminTenantController.class)
public class TenancyExceptionHandler {

    @ExceptionHandler(TenancyException.TenantNotFound.class)
    public ResponseEntity<ErrorResponse> handleTenantNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Tenant not found.", "TENANT_NOT_FOUND"));
    }

    @ExceptionHandler(TenancyException.DuplicateRcNumber.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRcNumber() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("A tenant with this RC number is already registered.", "RC_NUMBER_ALREADY_REGISTERED"));
    }

    // Defense-in-depth for the concurrent-create race the proactive
    // existsByRcNumberIgnoreCase check alone can't close — see
    // TenancyException.DuplicateRcNumber and changeset 027. Specifically
    // organizations.rc_number, not a catch-all: the primary contact's
    // email racing a concurrent write throws DuplicateEmailException
    // instead (see below), never reaches here.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("A tenant with this RC number is already registered.", "RC_NUMBER_ALREADY_REGISTERED"));
    }

    // Thrown by identity's TenantStaffAccountListener when the tenant's
    // primary contact's email is already in use by an existing account
    // (any kind — buyer, another tenant's staff, platform staff). Lives in
    // `common` (see DuplicateEmailException) specifically so this handler,
    // which cannot import anything from `identity`, can still catch it.
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("An account with this email already exists.", "EMAIL_ALREADY_REGISTERED"));
    }

    @ExceptionHandler(TenancyException.NoDocumentsUploaded.class)
    public ResponseEntity<ErrorResponse> handleNoDocumentsUploaded() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("At least one document must be uploaded before documents can be submitted for review.", "NO_DOCUMENTS_UPLOADED"));
    }

    @ExceptionHandler(TenancyException.DocumentNotFound.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Document not found for this tenant.", "DOCUMENT_NOT_FOUND"));
    }

    @ExceptionHandler(TenancyException.DecisionReasonRequired.class)
    public ResponseEntity<ErrorResponse> handleDecisionReasonRequired() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.fieldErrors(Map.of("reason", "A reason is required for REJECTED and REQUEST_MORE_INFO decisions.")));
    }

    @ExceptionHandler(TenancyException.InvalidVerificationTransition.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationTransition(TenancyException.InvalidVerificationTransition ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "INVALID_VERIFICATION_TRANSITION"));
    }

    @ExceptionHandler(TenancyException.StatusReasonRequired.class)
    public ResponseEntity<ErrorResponse> handleStatusReasonRequired() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.fieldErrors(Map.of("reason", "A reason is required when suspending or offboarding a tenant.")));
    }

    @ExceptionHandler(TenancyException.InvalidStatusTransition.class)
    public ResponseEntity<ErrorResponse> handleInvalidStatusTransition(TenancyException.InvalidStatusTransition ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "INVALID_STATUS_TRANSITION"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        // Covers VerificationState/VerificationDecisionType/TenantPlan/etc.
        // fromValue(...) rejecting an unrecognised wire value.
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "INVALID_VALUE"));
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Request body is malformed or contains unexpected fields.", "MALFORMED_REQUEST"));
    }
}
