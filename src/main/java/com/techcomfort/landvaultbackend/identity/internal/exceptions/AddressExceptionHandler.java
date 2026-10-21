package com.techcomfort.landvaultbackend.identity.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.identity.internal.controllers.AddressController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns address refusals into AGENTS.md's {message, code, fieldErrors} body. */
@RestControllerAdvice(assignableTypes = AddressController.class)
public class AddressExceptionHandler {

    @ExceptionHandler(AddressException.class)
    public ResponseEntity<ErrorResponse> handle(AddressException ex) {
        String code = ex instanceof AddressException.UnknownState ? "UNKNOWN_STATE" : "UNKNOWN_COUNTRY";
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ex.getMessage(), code, Map.of(ex.field(), ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(), (a, b) -> a));
        return ResponseEntity.badRequest().body(ErrorResponse.fieldErrors(fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Request body is malformed.", "MALFORMED_REQUEST"));
    }
}
