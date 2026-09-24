package com.techcomfort.landvaultbackend.checkout.internal.exceptions;

import com.techcomfort.landvaultbackend.checkout.internal.controllers.CheckoutTransactionController;
import com.techcomfort.landvaultbackend.checkout.internal.controllers.ReservationController;
import com.techcomfort.landvaultbackend.common.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns the checkout exceptions into AGENTS.md's {message, code, fieldErrors} shape. */
@RestControllerAdvice(assignableTypes = {
        ReservationController.class, CheckoutTransactionController.class})
public class CheckoutExceptionHandler {

    /**
     * 409, not 404: the buyer asked for something reasonable and lost, or
     * the plot moved on. A 4xx that is never retried is the right signal —
     * retrying will not make the plot come back.
     */
    @ExceptionHandler(CheckoutException.PlotNotAvailable.class)
    public ResponseEntity<ErrorResponse> handlePlotNotAvailable(CheckoutException.PlotNotAvailable ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "PLOT_NOT_AVAILABLE"));
    }

    @ExceptionHandler(CheckoutException.EstateNotAvailable.class)
    public ResponseEntity<ErrorResponse> handleEstateNotAvailable(CheckoutException.EstateNotAvailable ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "ESTATE_NOT_AVAILABLE"));
    }

    /**
     * 403 with its own code rather than a generic one, so the frontend can
     * route the buyer into verification instead of showing them a dead end.
     */
    @ExceptionHandler(CheckoutException.KycRequired.class)
    public ResponseEntity<ErrorResponse> handleKycRequired(CheckoutException.KycRequired ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ex.getMessage(), "KYC_REQUIRED"));
    }

    @ExceptionHandler(CheckoutException.ReservationNotFound.class)
    public ResponseEntity<ErrorResponse> handleReservationNotFound(CheckoutException.ReservationNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getMessage(), "RESERVATION_NOT_FOUND"));
    }

    @ExceptionHandler(CheckoutException.TransactionNotFound.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(CheckoutException.TransactionNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getMessage(), "TRANSACTION_NOT_FOUND"));
    }

    @ExceptionHandler(CheckoutException.ReservationNotActive.class)
    public ResponseEntity<ErrorResponse> handleReservationNotActive(CheckoutException.ReservationNotActive ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "RESERVATION_NOT_ACTIVE"));
    }

    @ExceptionHandler(CheckoutException.TransactionAlreadyExists.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(CheckoutException.TransactionAlreadyExists ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "TRANSACTION_ALREADY_EXISTS"));
    }

    @ExceptionHandler(CheckoutException.InvalidPaymentPlan.class)
    public ResponseEntity<ErrorResponse> handleInvalidPlan(CheckoutException.InvalidPaymentPlan ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ex.getMessage(), "INVALID_PAYMENT_PLAN"));
    }

    /** An unknown intent/plan wire value reaches here from the enums' fromValue. */
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
