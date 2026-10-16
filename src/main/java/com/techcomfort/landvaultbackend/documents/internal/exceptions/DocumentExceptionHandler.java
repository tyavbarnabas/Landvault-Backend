package com.techcomfort.landvaultbackend.documents.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.documents.internal.controllers.DocumentController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.DocumentVerificationController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every error is sent as JSON with its content type set explicitly. A download
 * is requested with {@code Accept: application/pdf} (or {@code image/jpeg}), and
 * without a preset type Spring negotiates the error body against that header,
 * finds no JSON in it, and answers an empty 500 instead of the 404.
 */
@RestControllerAdvice(assignableTypes = {DocumentController.class, DocumentVerificationController.class})
public class DocumentExceptionHandler {

    @ExceptionHandler(DocumentException.DocumentNotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound(DocumentException.DocumentNotFound ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), "DOCUMENT_NOT_FOUND");
    }

    @ExceptionHandler(DocumentException.PlotNotFound.class)
    public ResponseEntity<ErrorResponse> handlePlotNotFound(DocumentException.PlotNotFound ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), "PORTFOLIO_PLOT_NOT_FOUND");
    }

    @ExceptionHandler(DocumentException.NotAHomeTrueDocument.class)
    public ResponseEntity<ErrorResponse> handleNotOurs(DocumentException.NotAHomeTrueDocument ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), "NOT_A_HOMETRUE_DOCUMENT");
    }

    /** A path value of the wrong type, e.g. an id that isn't a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "'" + ex.getName() + "' isn't a valid value.", "INVALID_PARAMETER");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message, String code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(message, code));
    }
}
