package com.techcomfort.landvaultbackend.identity.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.identity.internal.controllers.PortalRoleController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns company-role refusals into AGENTS.md's {message, code, fieldErrors} body. */
@RestControllerAdvice(assignableTypes = PortalRoleController.class)
public class RoleExceptionHandler {

    @ExceptionHandler(RoleException.class)
    public ResponseEntity<ErrorResponse> handle(RoleException ex) {
        return switch (ex) {
            case RoleException.NotFound e -> body(HttpStatus.NOT_FOUND, e, "ROLE_NOT_FOUND");
            case RoleException.StandardRoleLocked e -> body(HttpStatus.FORBIDDEN, e, "STANDARD_ROLE_LOCKED");
            case RoleException.CompanyWideOnly e -> body(HttpStatus.FORBIDDEN, e, "ROLES_REQUIRE_COMPANY_WIDE_SCOPE");
            case RoleException.UnknownPermission e -> body(HttpStatus.BAD_REQUEST, e, "UNKNOWN_PERMISSION");
            case RoleException.CannotGrant e -> body(HttpStatus.FORBIDDEN, e, "CANNOT_GRANT_PERMISSION");
            case RoleException.Outranks e -> body(HttpStatus.FORBIDDEN, e, "CANNOT_MANAGE_ROLE");
            case RoleException.CompanyWidePermissionInNarrowRole e ->
                    body(HttpStatus.BAD_REQUEST, e, "PERMISSION_REQUIRES_COMPANY_SCOPE");
            case RoleException.InvalidScope e -> body(HttpStatus.BAD_REQUEST, e, "INVALID_ROLE_SCOPE");
            case RoleException.ScopeImmutable e -> body(HttpStatus.BAD_REQUEST, e, "ROLE_SCOPE_IMMUTABLE");
            case RoleException.NameTaken e -> body(HttpStatus.CONFLICT, e, "ROLE_NAME_TAKEN");
            case RoleException.LimitReached e -> body(HttpStatus.CONFLICT, e, "ROLE_LIMIT_REACHED");
            case RoleException.InUse e -> body(HttpStatus.CONFLICT, e, "ROLE_IN_USE");
            case RoleException.InvalidName e -> body(HttpStatus.BAD_REQUEST, e, "INVALID_ROLE_NAME");
            case RoleException.NoPermissions e -> body(HttpStatus.BAD_REQUEST, e, "ROLE_NEEDS_PERMISSIONS");
            default -> body(HttpStatus.BAD_REQUEST, ex, "ROLE_CHANGE_REFUSED");
        };
    }

    /** Two creates racing on one name: the partial unique index is the backstop for the service's check. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("Your company already has a role with that name.", "ROLE_NAME_TAKEN"));
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

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, RoleException ex, String code) {
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.getMessage(), code));
    }
}
