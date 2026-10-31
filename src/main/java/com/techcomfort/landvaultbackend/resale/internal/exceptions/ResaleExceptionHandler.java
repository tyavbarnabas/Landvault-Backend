package com.techcomfort.landvaultbackend.resale.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.resale.internal.controllers.PortalResaleController;
import com.techcomfort.landvaultbackend.resale.internal.controllers.ResaleController;
import com.techcomfort.landvaultbackend.resale.internal.controllers.ResaleListingController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {ResaleController.class, ResaleListingController.class,
        PortalResaleController.class})
public class ResaleExceptionHandler {

    @ExceptionHandler(ResaleException.PlotNotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResaleException.PlotNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage(), "OWNED_PLOT_NOT_FOUND"));
    }

    @ExceptionHandler(ResaleException.InvalidPrice.class)
    public ResponseEntity<ErrorResponse> handleInvalidPrice(ResaleException.InvalidPrice ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "INVALID_PRICE"));
    }

    /** A path or query value of the wrong type, e.g. an id that isn't a UUID or a price that isn't a number. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("'" + ex.getName() + "' isn't a valid value.", "INVALID_PARAMETER"));
    }

    @ExceptionHandler(ResaleException.NotAllowed.class)
    public ResponseEntity<ErrorResponse> handleNotAllowed(ResaleException.NotAllowed ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(ex.getMessage(), "RESALE_NOT_ALLOWED"));
    }

    @ExceptionHandler(ResaleException.AlreadyListed.class)
    public ResponseEntity<ErrorResponse> handleAlreadyListed(ResaleException.AlreadyListed ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(ex.getMessage(), "ALREADY_LISTED"));
    }

    /** Two listings racing past the service check: the one-live-listing index is the backstop. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleRace(DataIntegrityViolationException ex) {
        return handleAlreadyListed(new ResaleException.AlreadyListed());
    }

    @ExceptionHandler(ResaleException.ListingNotFound.class)
    public ResponseEntity<ErrorResponse> handleListingNotFound(ResaleException.ListingNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage(), "LISTING_NOT_FOUND"));
    }

    @ExceptionHandler(ResaleException.ListingNotLive.class)
    public ResponseEntity<ErrorResponse> handleNotLive(ResaleException.ListingNotLive ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(ex.getMessage(), "LISTING_NOT_LIVE"));
    }

    @ExceptionHandler(ResaleException.ConsentNotAwaited.class)
    public ResponseEntity<ErrorResponse> handleNotAwaited(ResaleException.ConsentNotAwaited ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(ex.getMessage(), "CONSENT_NOT_AWAITED"));
    }

    @ExceptionHandler(ResaleException.ConsentRequestLapsed.class)
    public ResponseEntity<ErrorResponse> handleLapsed(ResaleException.ConsentRequestLapsed ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(ex.getMessage(), "CONSENT_REQUEST_LAPSED"));
    }

    @ExceptionHandler(ResaleException.ConsentRequiresCompanyWideScope.class)
    public ResponseEntity<ErrorResponse> handleScope(ResaleException.ConsentRequiresCompanyWideScope ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ex.getMessage(), "RESALE_CONSENT_REQUIRES_COMPANY_WIDE_SCOPE"));
    }

    /** An unknown {@code status} filter. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadValue(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "VALIDATION_ERROR"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ErrorResponse("Some fields are invalid.", "VALIDATION_ERROR", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("The request body couldn't be read.", "MALFORMED_REQUEST"));
    }
}
