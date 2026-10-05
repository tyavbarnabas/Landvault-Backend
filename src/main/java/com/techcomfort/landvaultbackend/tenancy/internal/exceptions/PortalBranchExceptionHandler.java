package com.techcomfort.landvaultbackend.tenancy.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.tenancy.internal.controllers.PortalBranchController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Its own handler, deliberately not {@code TenancyExceptionHandler}: that one
 * reports every {@code DataIntegrityViolationException} as a duplicate RC
 * number, and the only unique constraint a branch write can hit is the name.
 */
@RestControllerAdvice(assignableTypes = PortalBranchController.class)
public class PortalBranchExceptionHandler {

    @ExceptionHandler(PortalBranchException.NotFound.class)
    public ResponseEntity<ErrorResponse> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Branch not found.", "BRANCH_NOT_FOUND"));
    }

    @ExceptionHandler(PortalBranchException.NoTenant.class)
    public ResponseEntity<ErrorResponse> handleNoTenant() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Branches belong to a company; this account has none.", "NO_TENANT_SCOPE"));
    }

    @ExceptionHandler(PortalBranchException.CompanyWideOnly.class)
    public ResponseEntity<ErrorResponse> handleCompanyWideOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("Branches are managed company-wide. Switch to the whole-company view to "
                        + "create or rename one.", "BRANCHES_REQUIRE_COMPANY_WIDE_SCOPE"));
    }

    @ExceptionHandler(PortalBranchException.BlankName.class)
    public ResponseEntity<ErrorResponse> handleBlankName() {
        return ResponseEntity.badRequest().body(ErrorResponse.of("A branch name cannot be blank.", "INVALID_REQUEST"));
    }

    @ExceptionHandler(PortalBranchException.UnknownState.class)
    public ResponseEntity<ErrorResponse> handleUnknownState(PortalBranchException.UnknownState ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage(), "UNKNOWN_STATE"));
    }

    @ExceptionHandler(PortalBranchException.NameTaken.class)
    public ResponseEntity<ErrorResponse> handleNameTaken(PortalBranchException.NameTaken ex) {
        return nameTaken("Your company already has a branch called '" + ex.name() + "'.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleRace() {
        return nameTaken("Your company already has a branch with that name.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(), (a, b) -> a));
        return ResponseEntity.badRequest().body(ErrorResponse.fieldErrors(fieldErrors));
    }

    private static ResponseEntity<ErrorResponse> nameTaken(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(message, "BRANCH_NAME_TAKEN"));
    }
}
