package com.techcomfort.landvaultbackend.payments.internal.exceptions;

import com.techcomfort.landvaultbackend.common.ErrorResponse;
import com.techcomfort.landvaultbackend.payments.internal.controllers.AdminPayoutController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.AdminRefundController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.AdminSettlementAccountController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.PaymentController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.PaystackWebhookController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.PortalFinanceController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.PortalLatePaymentController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.PortalPayoutController;
import com.techcomfort.landvaultbackend.payments.internal.controllers.PortalSettlementController;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/** Payment refusals in AGENTS.md's {message, code, fieldErrors} shape. */
@RestControllerAdvice(assignableTypes = {PaymentController.class, PaystackWebhookController.class,
        PortalFinanceController.class, PortalSettlementController.class, AdminSettlementAccountController.class,
        AdminPayoutController.class, PortalPayoutController.class, AdminRefundController.class,
        PortalLatePaymentController.class})
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ErrorResponse> handle(PaymentException ex) {
        return switch (ex) {
            case PaymentException.TransactionNotFound e -> body(HttpStatus.NOT_FOUND, e, "TRANSACTION_NOT_FOUND");
            case PaymentException.PaymentNotFound e -> body(HttpStatus.NOT_FOUND, e, "PAYMENT_NOT_FOUND");
            case PaymentException.WebhookSignatureInvalid e -> body(HttpStatus.UNAUTHORIZED, e, "WEBHOOK_SIGNATURE_INVALID");
            case PaymentException.CompanyScopeRequired e -> body(HttpStatus.FORBIDDEN, e, "COMPANY_SCOPE_REQUIRED");
            case PaymentException.NotAwaitingFinance e -> body(HttpStatus.CONFLICT, e, "TRANSACTION_NOT_AWAITING_FINANCE");
            case PaymentException.PlotNotReserved e -> body(HttpStatus.CONFLICT, e, "PLOT_NOT_RESERVED");
            case PaymentException.PaymentNotConfirmed e -> body(HttpStatus.CONFLICT, e, "PAYMENT_NOT_CONFIRMED");
            case PaymentException.TransactionNotPayable e -> body(HttpStatus.CONFLICT, e, "TRANSACTION_NOT_PAYABLE");
            case PaymentException.PlanNotSupported e -> body(HttpStatus.BAD_REQUEST, e, "PAYMENT_PLAN_NOT_SUPPORTED");
            case PaymentException.UnsupportedCurrency e -> body(HttpStatus.BAD_REQUEST, e, "CURRENCY_NOT_SUPPORTED");
            case PaymentException.SettlementRequiresCompanyWideScope e -> body(HttpStatus.FORBIDDEN, e, "SETTLEMENT_REQUIRES_COMPANY_WIDE_SCOPE");
            case PaymentException.UnknownBank e -> body(HttpStatus.BAD_REQUEST, e, "UNKNOWN_BANK");
            case PaymentException.AccountNotResolved e -> body(HttpStatus.BAD_REQUEST, e, "ACCOUNT_NOT_RESOLVED");
            case PaymentException.SettlementAccountUnchanged e -> body(HttpStatus.BAD_REQUEST, e, "SETTLEMENT_ACCOUNT_UNCHANGED");
            case PaymentException.SettlementAccountPending e -> body(HttpStatus.CONFLICT, e, "SETTLEMENT_ACCOUNT_PENDING");
            case PaymentException.SettlementAccountNotFound e -> body(HttpStatus.NOT_FOUND, e, "SETTLEMENT_ACCOUNT_NOT_FOUND");
            case PaymentException.SettlementAccountNotPending e -> body(HttpStatus.CONFLICT, e, "SETTLEMENT_ACCOUNT_NOT_PENDING");
            case PaymentException.TwoFactorRequired e -> body(HttpStatus.FORBIDDEN, e, "TWO_FACTOR_REQUIRED");
            case PaymentException.SaleNotPayable e -> body(HttpStatus.NOT_FOUND, e, "SALE_NOT_PAYABLE");
            case PaymentException.LatePaymentNotFound e -> body(HttpStatus.NOT_FOUND, e, "LATE_PAYMENT_NOT_FOUND");
            case PaymentException.PlotNoLongerAvailable e -> body(HttpStatus.CONFLICT, e, "PLOT_NO_LONGER_AVAILABLE");
            case PaymentException.RefundNotDue e -> body(HttpStatus.NOT_FOUND, e, "REFUND_NOT_DUE");
            case PaymentException.RefundInProgress e -> body(HttpStatus.CONFLICT, e, "REFUND_IN_PROGRESS");
            case PaymentException.RefundNotFound e -> body(HttpStatus.NOT_FOUND, e, "REFUND_NOT_FOUND");
            case PaymentException.RefundNotAwaitingAccount e -> body(HttpStatus.CONFLICT, e, "REFUND_NOT_AWAITING_ACCOUNT");
            case PaymentException.RefundAccountMissing e -> body(HttpStatus.CONFLICT, e, "REFUND_ACCOUNT_MISSING");
            case PaymentException.RefundOutcomeUnknown e -> body(HttpStatus.SERVICE_UNAVAILABLE, e, "REFUND_OUTCOME_UNKNOWN");
            case PaymentException.CompanyWideScopeRequired e -> body(HttpStatus.FORBIDDEN, e, "COMPANY_WIDE_SCOPE_REQUIRED");
            case PaymentException.SaleAlreadyPaid e -> body(HttpStatus.CONFLICT, e, "SALE_ALREADY_PAID");
            case PaymentException.PayoutInProgress e -> body(HttpStatus.CONFLICT, e, "PAYOUT_IN_PROGRESS");
            case PaymentException.CompanyNotActive e -> body(HttpStatus.CONFLICT, e, "COMPANY_NOT_ACTIVE");
            case PaymentException.NoApprovedPayoutAccount e -> body(HttpStatus.CONFLICT, e, "NO_APPROVED_PAYOUT_ACCOUNT");
            case PaymentException.PayoutNotFound e -> body(HttpStatus.NOT_FOUND, e, "PAYOUT_NOT_FOUND");
            case PaymentException.PayoutNotAwaitingOtp e -> body(HttpStatus.CONFLICT, e, "PAYOUT_NOT_AWAITING_OTP");
            case PaymentException.OtpRejected e -> body(HttpStatus.BAD_REQUEST, e, "OTP_REJECTED");
            case PaymentException.PayoutOutcomeUnknown e -> body(HttpStatus.SERVICE_UNAVAILABLE, e, "PAYOUT_OUTCOME_UNKNOWN");
            case PaymentException.UnknownStatusFilter e -> body(HttpStatus.BAD_REQUEST, e, "INVALID_STATUS");
            case PaymentException.InvalidAmount e -> body(HttpStatus.BAD_REQUEST, e, "INVALID_AMOUNT");
            // Paystack refusing OUR request is our fault, not the buyer's: a bad gateway, not a 4xx.
            case PaymentException.GatewayRefused e -> body(HttpStatus.BAD_GATEWAY, e, "PAYMENT_PROVIDER_REFUSED");
            case PaymentException.GatewayUnavailable e -> body(HttpStatus.SERVICE_UNAVAILABLE, e, "PAYMENT_PROVIDER_UNAVAILABLE");
            default -> body(HttpStatus.BAD_REQUEST, ex, "PAYMENT_REFUSED");
        };
    }

    /**
     * A request body that fails its own rules (a blank reason, a 9-digit
     * account number, a code with letters): 400 VALIDATION_ERROR with the
     * field and why, in the standard shape — never Spring's default body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
                        (first, second) -> first));
        return ResponseEntity.badRequest().body(ErrorResponse.fieldErrors(fieldErrors));
    }

    /** Not JSON, or no body where one is required. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Request body is malformed or contains unexpected fields.", "MALFORMED_REQUEST"));
    }

    /** A path or query value of the wrong type, e.g. an id that isn't a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("'" + ex.getName() + "' isn't a valid value.", "INVALID_PARAMETER"));
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, PaymentException ex, String code) {
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.getMessage(), code));
    }
}
