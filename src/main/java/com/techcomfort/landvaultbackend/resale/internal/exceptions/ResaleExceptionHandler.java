package com.techcomfort.landvaultbackend.resale.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.resale.internal.controllers.ResaleController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ResaleController.class)
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
}
