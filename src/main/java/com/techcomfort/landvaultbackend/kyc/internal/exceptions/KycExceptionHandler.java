package com.techcomfort.landvaultbackend.kyc.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.kyc.internal.controllers.AdminKycController;
import com.techcomfort.landvaultbackend.kyc.internal.controllers.KycController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns the KYC exceptions into AGENTS.md's {message, code, fieldErrors} shape. */
@RestControllerAdvice(assignableTypes = {KycController.class, AdminKycController.class})
public class KycExceptionHandler {

    @ExceptionHandler(KycException.MissingDocuments.class)
    public ResponseEntity<ErrorResponse> handleMissing(KycException.MissingDocuments ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ex.getMessage(), "MISSING_KYC_DOCUMENTS"));
    }

    @ExceptionHandler(KycException.RecordNotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound(KycException.RecordNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getMessage(), "KYC_RECORD_NOT_FOUND"));
    }

    @ExceptionHandler(KycException.BuyerNotFound.class)
    public ResponseEntity<ErrorResponse> handleBuyerNotFound(KycException.BuyerNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getMessage(), "BUYER_NOT_FOUND"));
    }

    @ExceptionHandler(KycException.InvalidDecision.class)
    public ResponseEntity<ErrorResponse> handleInvalidDecision(KycException.InvalidDecision ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ex.getMessage(), "INVALID_KYC_DECISION"));
    }

    /** An unknown document-type wire value reaches here from the enum's fromValue. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleUnknownValue(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "VALIDATION_ERROR"));
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
