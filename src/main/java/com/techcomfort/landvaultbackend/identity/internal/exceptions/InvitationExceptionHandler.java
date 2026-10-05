package com.techcomfort.landvaultbackend.identity.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.identity.internal.controllers.InvitationAcceptController;
import com.techcomfort.landvaultbackend.identity.internal.controllers.PortalStaffInvitationController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Turns invitation refusals into AGENTS.md's {message, code, fieldErrors} body. */
@RestControllerAdvice(assignableTypes = {PortalStaffInvitationController.class, InvitationAcceptController.class})
public class InvitationExceptionHandler {

    @ExceptionHandler(InvitationException.class)
    public ResponseEntity<ErrorResponse> handle(InvitationException ex) {
        return switch (ex) {
            case InvitationException.Invalid e -> body(HttpStatus.BAD_REQUEST, e, "INVITATION_INVALID");
            case InvitationException.NotFound e -> body(HttpStatus.NOT_FOUND, e, "INVITATION_NOT_FOUND");
            case InvitationException.CompanyWideOnly e -> body(HttpStatus.FORBIDDEN, e, "INVITATIONS_REQUIRE_COMPANY_WIDE_SCOPE");
            case InvitationException.TenantNotActive e -> body(HttpStatus.FORBIDDEN, e, "TENANT_NOT_ACTIVE");
            case InvitationException.RoleNotInvitable e -> body(HttpStatus.BAD_REQUEST, e, "ROLE_NOT_INVITABLE");
            case InvitationException.CannotGrant e -> body(HttpStatus.FORBIDDEN, e, "CANNOT_GRANT_ROLE");
            case InvitationException.ScopeMismatch e -> body(HttpStatus.BAD_REQUEST, e, "ROLE_SCOPE_MISMATCH");
            case InvitationException.BranchNotFound e -> body(HttpStatus.BAD_REQUEST, e, "BRANCH_NOT_FOUND");
            case InvitationException.EmailHasAccount e -> body(HttpStatus.CONFLICT, e, "EMAIL_HAS_ACCOUNT");
            case InvitationException.AlreadyPending e -> body(HttpStatus.CONFLICT, e, "INVITATION_ALREADY_PENDING");
            case InvitationException.NotOpen e -> body(HttpStatus.CONFLICT, e, "INVITATION_NOT_OPEN");
            case InvitationException.BranchScopeOnly e -> body(HttpStatus.FORBIDDEN, e, "INVITATION_REQUESTS_REQUIRE_BRANCH_SCOPE");
            case InvitationException.NotAwaitingApproval e -> body(HttpStatus.CONFLICT, e, "INVITATION_NOT_AWAITING_APPROVAL");
            case InvitationException.RequestExpired e -> body(HttpStatus.CONFLICT, e, "INVITATION_REQUEST_EXPIRED");
            case InvitationException.AwaitingApproval e -> body(HttpStatus.CONFLICT, e, "INVITATION_AWAITING_APPROVAL");
            case InvitationException.ResendTooSoon e -> body(HttpStatus.TOO_MANY_REQUESTS, e, "INVITATION_RESEND_TOO_SOON");
            case InvitationException.ResendLimitReached e -> body(HttpStatus.TOO_MANY_REQUESTS, e, "INVITATION_RESEND_LIMIT_REACHED");
            default -> body(HttpStatus.BAD_REQUEST, ex, "INVITATION_REFUSED");
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(), (a, b) -> a));
        return ResponseEntity.badRequest().body(ErrorResponse.fieldErrors(fieldErrors));
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, InvitationException ex, String code) {
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.getMessage(), code));
    }
}
