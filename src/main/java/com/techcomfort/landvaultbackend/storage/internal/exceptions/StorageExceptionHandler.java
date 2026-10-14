package com.techcomfort.landvaultbackend.storage.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.storage.StorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Storage refusals in AGENTS.md's {message, code, fieldErrors} shape, for
 * EVERY controller (not scoped by assignableTypes): any module that stores
 * files gets the same answers without listing them in its own handler.
 * Handles nothing but {@link StorageException}, so it changes no other
 * module's errors.
 */
@RestControllerAdvice
public class StorageExceptionHandler {

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handle(StorageException ex) {
        return switch (ex) {
            case StorageException.FileTypeNotAllowed e -> body(HttpStatus.BAD_REQUEST, e, "FILE_TYPE_NOT_ALLOWED");
            case StorageException.FileTooLarge e -> body(HttpStatus.BAD_REQUEST, e, "FILE_TOO_LARGE");
            case StorageException.UploadRejected e -> body(HttpStatus.BAD_REQUEST, e, e.code());
            case StorageException.FileNotFound e -> body(HttpStatus.NOT_FOUND, e, "FILE_NOT_FOUND");
            case StorageException.UploadNotReceived e -> body(HttpStatus.CONFLICT, e, "UPLOAD_NOT_RECEIVED");
            case StorageException.UploadExpired e -> body(HttpStatus.CONFLICT, e, "UPLOAD_EXPIRED");
            case StorageException.FileNotReady e -> body(HttpStatus.CONFLICT, e, "FILE_NOT_READY");
            case StorageException.StorageUnavailable e -> body(HttpStatus.SERVICE_UNAVAILABLE, e, "STORAGE_UNAVAILABLE");
            default -> body(HttpStatus.BAD_REQUEST, ex, "STORAGE_REFUSED");
        };
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, StorageException ex, String code) {
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.getMessage(), code));
    }
}
