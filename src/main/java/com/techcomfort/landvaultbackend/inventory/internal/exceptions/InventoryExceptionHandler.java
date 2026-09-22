package com.techcomfort.landvaultbackend.inventory.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.inventory.internal.controllers.PortalEstateController;
import org.hibernate.exception.ConstraintViolationException;
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

/** Turns the estate-creation exceptions into AGENTS.md's {message, code, fieldErrors} shape. */
@RestControllerAdvice(assignableTypes = PortalEstateController.class)
public class InventoryExceptionHandler {

    @ExceptionHandler(InventoryException.EstateNotFound.class)
    public ResponseEntity<ErrorResponse> handleEstateNotFound() {
        // Deliberately the same response whether the estate doesn't exist or
        // belongs to another tenant — otherwise this enumerates estate ids.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Estate not found.", "ESTATE_NOT_FOUND"));
    }

    @ExceptionHandler(InventoryException.PublicationRefused.class)
    public ResponseEntity<ErrorResponse> handlePublicationRefused(InventoryException.PublicationRefused ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(ex.getMessage(), ex.code()));
    }

    @ExceptionHandler(InventoryException.RelatedRecordNotFound.class)
    public ResponseEntity<ErrorResponse> handleRelatedRecordNotFound(InventoryException.RelatedRecordNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getMessage(), "RELATED_RECORD_NOT_FOUND"));
    }

    @ExceptionHandler(InventoryException.DuplicateRecord.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(InventoryException.DuplicateRecord ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getMessage(), "DUPLICATE_RECORD"));
    }

    @ExceptionHandler(InventoryException.InvalidGeometry.class)
    public ResponseEntity<ErrorResponse> handleInvalidGeometry(InventoryException.InvalidGeometry ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ex.getMessage(), "INVALID_GEOMETRY", Map.of(ex.field(), ex.getMessage())));
    }

    @ExceptionHandler(InventoryException.PlotOutsideEstate.class)
    public ResponseEntity<ErrorResponse> handlePlotOutsideEstate(InventoryException.PlotOutsideEstate ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ex.getMessage(), "PLOT_OUTSIDE_ESTATE"));
    }

    @ExceptionHandler(InventoryException.InvalidRequest.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InventoryException.InvalidRequest ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "INVALID_REQUEST"));
    }

    /**
     * A duplicate plot number, block name or tier size is an ordinary user
     * error, not a server fault — without this it surfaces as a 500.
     * <p>
     * The constraint name is inspected rather than reporting one fixed
     * message: AGENTS.md already records the tenancy lesson that a handler
     * written for a single constraint mislabels every other collision it
     * catches. Anything unrecognised says so plainly instead of guessing.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(DataIntegrityViolationException ex) {
        String constraint = constraintNameOf(ex);
        String message = switch (constraint == null ? "" : constraint) {
            case "uq_plots_estate_block_number" ->
                    "That plot number already exists in this block. Plot numbers are unique per block, "
                            + "and per estate for plots with no block.";
            case "uq_blocks_estate_name" -> "A block with that name already exists on this estate.";
            case "uq_price_tiers_estate_size" -> "A tier for that size already exists on this estate.";
            case "uq_estates_tenant_slug" -> "An estate with a matching name already exists for this tenant.";
            case "uq_estate_titles_estate_id" -> "This estate already has a title recorded.";
            case "uq_estate_verification_checks_estate_type" ->
                    "This estate already has a check of that type recorded.";
            case "uq_estate_amenities_estate_name" -> "That amenity is already listed on this estate.";
            default -> "That change conflicts with existing data"
                    + (constraint == null ? "." : " (" + constraint + ").");
        };
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(message, "DUPLICATE_RECORD"));
    }

    private static String constraintNameOf(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
            cause = cause.getCause();
        }
        return null;
    }

    // Covers every enum's fromValue(...) rejecting an unrecognised wire value.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
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
