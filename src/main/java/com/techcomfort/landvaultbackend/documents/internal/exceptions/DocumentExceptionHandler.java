package com.techcomfort.landvaultbackend.documents.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.documents.internal.controllers.DocumentController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.DocumentVerificationController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.EstateLetterTermsController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.LetterApprovalController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.LetterTemplateController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.LetterTemplateReviewController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.LetterTermsReviewController;
import com.techcomfort.landvaultbackend.documents.internal.controllers.LetterheadController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error is sent as JSON with its content type set explicitly. A download
 * is requested with {@code Accept: application/pdf} (or {@code image/jpeg}), and
 * without a preset type Spring negotiates the error body against that header,
 * finds no JSON in it, and answers an empty 500 instead of the 404.
 */
@RestControllerAdvice(assignableTypes = {DocumentController.class, DocumentVerificationController.class,
        LetterheadController.class, EstateLetterTermsController.class, LetterTermsReviewController.class,
        LetterApprovalController.class, LetterTemplateController.class, LetterTemplateReviewController.class})
public class DocumentExceptionHandler {

    @ExceptionHandler(DocumentException.DocumentNotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound(DocumentException.DocumentNotFound ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), "DOCUMENT_NOT_FOUND");
    }

    @ExceptionHandler(DocumentException.PlotNotFound.class)
    public ResponseEntity<ErrorResponse> handlePlotNotFound(DocumentException.PlotNotFound ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), "OWNED_PLOT_NOT_FOUND");
    }

    @ExceptionHandler(DocumentException.NotAHomeTrueDocument.class)
    public ResponseEntity<ErrorResponse> handleNotOurs(DocumentException.NotAHomeTrueDocument ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), "NOT_A_HOMETRUE_DOCUMENT");
    }

    @ExceptionHandler(DocumentException.NotFound.class)
    public ResponseEntity<ErrorResponse> handleCodedNotFound(DocumentException.NotFound ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), ex.code());
    }

    @ExceptionHandler(DocumentException.RequestRejected.class)
    public ResponseEntity<ErrorResponse> handleRejected(DocumentException.RequestRejected ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.code());
    }

    @ExceptionHandler(DocumentException.Conflict.class)
    public ResponseEntity<ErrorResponse> handleStateConflict(DocumentException.Conflict ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), ex.code());
    }

    @ExceptionHandler(DocumentException.CompanyScopeRequired.class)
    public ResponseEntity<ErrorResponse> handleNoCompany(DocumentException.CompanyScopeRequired ex) {
        return error(HttpStatus.FORBIDDEN, ex.getMessage(), "COMPANY_SCOPE_REQUIRED");
    }

    @ExceptionHandler(DocumentException.CompanyWideOnly.class)
    public ResponseEntity<ErrorResponse> handleCompanyWideOnly(DocumentException.CompanyWideOnly ex) {
        return error(HttpStatus.FORBIDDEN, ex.getMessage(), "DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE");
    }

    @ExceptionHandler(DocumentException.ClausesRefused.class)
    public ResponseEntity<ErrorResponse> handleClausesRefused(DocumentException.ClausesRefused ex) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), "CLAUSE_REFUSED", ex.problems()));
    }

    @ExceptionHandler(DocumentException.TemplateRefused.class)
    public ResponseEntity<ErrorResponse> handleTemplateRefused(DocumentException.TemplateRefused ex) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), "TEMPLATE_REFUSED", ex.problems()));
    }

    /** Two changes at once: a unique index lets one through. Named by the constraint that refused the other. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException ex) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (detail.contains("uq_estate_letter_terms_version")) {
            return error(HttpStatus.CONFLICT, "Someone else saved this estate's conditions at the same moment. "
                    + "Reload and try again.", "LETTER_TERMS_CHANGED");
        }
        if (detail.contains("uq_letter_templates_version")) {
            return error(HttpStatus.CONFLICT, "Someone else saved this letter template at the same moment. "
                    + "Reload and try again.", "LETTER_TEMPLATE_CHANGED");
        }
        if (detail.contains("uq_letter_template_reviews_template")) {
            return error(HttpStatus.CONFLICT, "Another reviewer decided this version at the same moment.",
                    "LETTER_TEMPLATE_ALREADY_DECIDED");
        }
        if (detail.contains("uq_estate_letter_terms_reviews_terms")) {
            return error(HttpStatus.CONFLICT, "Another reviewer decided this version at the same moment.",
                    "LETTER_TERMS_ALREADY_DECIDED");
        }
        return error(HttpStatus.CONFLICT, "Someone else changed this letterhead at the same moment. Reload and try again.",
                "LETTERHEAD_CHANGED");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("Some fields are invalid.", "VALIDATION_ERROR", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "The request body could not be read.", "MALFORMED_REQUEST");
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
