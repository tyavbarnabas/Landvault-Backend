package com.techcomfort.landvaultbackend.marketplace.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.marketplace.internal.controllers.WishlistController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = WishlistController.class)
public class WishlistExceptionHandler {

    @ExceptionHandler(WishlistException.TypeUnknown.class)
    public ResponseEntity<ErrorResponse> handleTypeUnknown(WishlistException.TypeUnknown ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "WISHLIST_TYPE_UNKNOWN"));
    }

    @ExceptionHandler(WishlistException.TypeNotSupported.class)
    public ResponseEntity<ErrorResponse> handleTypeNotSupported(WishlistException.TypeNotSupported ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "WISHLIST_TYPE_NOT_SUPPORTED"));
    }

    @ExceptionHandler(WishlistException.ListingNotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound(WishlistException.ListingNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage(), "LISTING_NOT_FOUND"));
    }

    @ExceptionHandler(WishlistException.Full.class)
    public ResponseEntity<ErrorResponse> handleFull(WishlistException.Full ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(ex.getMessage(), "WISHLIST_FULL"));
    }

    /** A path value of the wrong type, e.g. a listing id that isn't a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("'" + ex.getName() + "' isn't a valid value.", "INVALID_PARAMETER"));
    }
}
