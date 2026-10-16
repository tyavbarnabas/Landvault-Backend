package com.techcomfort.landvaultbackend.portfolio.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.portfolio.internal.controllers.PortfolioController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PortfolioController.class)
public class PortfolioExceptionHandler {

    @ExceptionHandler(PortfolioException.PlotNotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PortfolioException.PlotNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage(), "OWNED_PLOT_NOT_FOUND"));
    }

    @ExceptionHandler(PortfolioException.InvalidParameter.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameter(PortfolioException.InvalidParameter ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "INVALID_PARAMETER"));
    }

    /** A path value of the wrong type, e.g. an id that isn't a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("'" + ex.getName() + "' isn't a valid value.", "INVALID_PARAMETER"));
    }
}
