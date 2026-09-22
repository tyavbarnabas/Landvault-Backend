package com.techcomfort.landvaultbackend.conflicts.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.conflicts.internal.controllers.AdminListingConflictController;
import com.techcomfort.landvaultbackend.conflicts.internal.controllers.PortalEstateConflictController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns the conflict-review exceptions into AGENTS.md's {message, code, fieldErrors} shape. */
@RestControllerAdvice(assignableTypes = {
        AdminListingConflictController.class, PortalEstateConflictController.class})
public class ConflictExceptionHandler {

    @ExceptionHandler(ConflictException.ConflictNotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Conflict not found.", "CONFLICT_NOT_FOUND"));
    }

    @ExceptionHandler(ConflictException.EstateNotFound.class)
    public ResponseEntity<ErrorResponse> handleEstateNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Estate not found.", "ESTATE_NOT_FOUND"));
    }

    @ExceptionHandler(ConflictException.InvalidTransition.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(ConflictException.InvalidTransition ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "INVALID_CONFLICT_TRANSITION"));
    }

    /** An unknown severity/status/type wire value reaches here from the enums' fromValue. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleUnknownValue(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ex.getMessage(), "VALIDATION_ERROR"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
                        (first, second) -> first));
        return ResponseEntity.badRequest().body(ErrorResponse.fieldErrors(fieldErrors));
    }
}
