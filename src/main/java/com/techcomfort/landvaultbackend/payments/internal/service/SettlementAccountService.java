package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.BankDto;
import com.techcomfort.landvaultbackend.payments.dto.CompanySettlementDto;
import com.techcomfort.landvaultbackend.payments.dto.SettlementAccountDto;
import com.techcomfort.landvaultbackend.payments.dto.SettlementAccountReviewDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.SettlementAccount;
import com.techcomfort.landvaultbackend.payments.internal.enums.SettlementAccountStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackBankDirectory;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.SettlementAccountRepository;
import com.techcomfort.landvaultbackend.tenancy.CompanyBankProfile;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * TR-1/TR-2: where a company's payouts go (decided with the user). The
 * Executive Director submits a bank and number; LandVault asks the bank for
 * the name; every Executive Director is alerted; a Super Admin approves, and
 * only then is a Paystack recipient created. Nothing is ever overwritten —
 * see AGENTS.md, "Payments".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementAccountService {

    private final SettlementAccountRepository accounts;
    private final PaystackBankDirectory bankDirectory;
    private final PaystackClient paystack;
    private final TenancyApi tenancyApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final AuditApi auditApi;

    public List<BankDto> banks() {
        return bankDirectory.banks().stream().map(b -> new BankDto(b.code(), b.name())).toList();
    }

    // --- the company's side ---

    @Transactional(readOnly = true)
    public CompanySettlementDto forCompany() {
        UUID tenantId = companyWide(currentScope());
        List<SettlementAccount> history = accounts.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        return new CompanySettlementDto(
                history.stream().filter(a -> a.getStatus() == SettlementAccountStatus.APPROVED).findFirst()
                        .map(SettlementAccountService::toDto).orElse(null),
                history.stream().filter(a -> a.getStatus() == SettlementAccountStatus.PENDING).findFirst()
                        .map(SettlementAccountService::toDto).orElse(null),
                history.stream().map(SettlementAccountService::toDto).toList());
    }

    /**
     * Saved as {@code pending}; never paid to until a Super Admin approves.
     * The account name comes from the bank, never from the request.
     */
    @Transactional
    public SettlementAccountDto submit(String bankCode, String accountNumber) {
        TenantScope scope = currentScope();
        UUID tenantId = companyWide(scope);
        PaystackClient.Bank bank = bankDirectory.find(bankCode.trim()).orElseThrow(PaymentException.UnknownBank::new);

        boolean alreadyApproved = accounts.findByTenantIdAndStatus(tenantId, SettlementAccountStatus.APPROVED)
                .filter(a -> a.getBankCode().equals(bank.code()) && a.getAccountNumber().equals(accountNumber))
                .isPresent();
        if (alreadyApproved) {
            throw new PaymentException.SettlementAccountUnchanged();
        }
        if (accounts.findByTenantIdAndStatus(tenantId, SettlementAccountStatus.PENDING).isPresent()) {
            throw new PaymentException.SettlementAccountPending();
        }
        String bankAccountName = paystack.resolveAccountName(accountNumber, bank.code())
                .orElseThrow(PaymentException.AccountNotResolved::new);

        SettlementAccount account = SettlementAccount.builder()
                .tenantId(tenantId)
                .bankCode(bank.code())
                .bankName(bank.name())
                .accountNumber(accountNumber)
                .accountName(bankAccountName)
                .currency(Currency.NGN)
                .status(SettlementAccountStatus.PENDING)
                .submittedBy(scope.userId())
                .build();
        try {
            account = accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // The one-pending index: another submission landed at the same moment.
            throw new PaymentException.SettlementAccountPending();
        }
        auditApi.record(AuditEntryRequest.of(scope.userId(), "settlement_account.submitted", "settlement_account",
                account.getId(), tenantId, "Payout account submitted: " + bank.name() + " ending "
                        + last4(accountNumber) + ", bank name \"" + bankAccountName + "\"."));
        alertDirectors(account, scope.userId());
        log.info("Payout account {} submitted for tenant {} by {}", account.getId(), tenantId, scope.userId());
        return toDto(account);
    }

    /** The company takes a submission back before anyone decides. */
    @Transactional
    public SettlementAccountDto withdraw(UUID accountId) {
        TenantScope scope = currentScope();
        UUID tenantId = companyWide(scope);
        SettlementAccount account = accounts.findByIdForUpdate(accountId)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(PaymentException.SettlementAccountNotFound::new);
        requirePending(account);
        decide(account, SettlementAccountStatus.WITHDRAWN, scope.userId(), null);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "settlement_account.withdrawn", "settlement_account",
                account.getId(), tenantId, "Payout account submission withdrawn (" + account.getBankName()
                        + " ending " + last4(account.getAccountNumber()) + ")."));
        return toDto(account);
    }

    // --- LandVault's side ---

    @Transactional(readOnly = true)
    public List<SettlementAccountReviewDto> forReview(SettlementAccountStatus status) {
        List<SettlementAccount> rows = status == null
                ? accounts.findAllByOrderByCreatedAtDesc()
                : accounts.findAllByStatusOrderByCreatedAtAsc(status);
        return rows.stream().map(this::toReview).toList();
    }

    @Transactional(readOnly = true)
    public SettlementAccountReviewDto reviewOne(UUID accountId) {
        return accounts.findById(accountId).map(this::toReview)
                .orElseThrow(PaymentException.SettlementAccountNotFound::new);
    }

    /**
     * Only now is the account registered with Paystack (decided with the
     * user), so a rejected submission never reaches Paystack. If Paystack is
     * down, nothing changes and the approval can be tried again. The previous
     * approved account becomes {@code superseded}, kept as history.
     */
    @Transactional
    public SettlementAccountReviewDto approve(UUID accountId) {
        TenantScope scope = currentScope();
        SettlementAccount account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(PaymentException.SettlementAccountNotFound::new);
        requirePending(account);
        String companyName = companyName(account.getTenantId());
        String recipientCode = paystack.createRecipient(account.getAccountName(), account.getAccountNumber(),
                account.getBankCode(), "LandVault payouts: " + companyName);

        Optional<SettlementAccount> previous = accounts.findLockedByTenantIdAndStatus(account.getTenantId(),
                SettlementAccountStatus.APPROVED);
        previous.ifPresent(old -> {
            old.setStatus(SettlementAccountStatus.SUPERSEDED);
            // Flushed before the new one is approved: one approved account per company is a unique index.
            accounts.saveAndFlush(old);
        });
        account.setRecipientCode(recipientCode);
        decide(account, SettlementAccountStatus.APPROVED, scope.userId(), null);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "settlement_account.approved", "settlement_account",
                account.getId(), account.getTenantId(), "Payout account approved: " + account.getBankName()
                        + " ending " + last4(account.getAccountNumber()) + previous.map(old -> ", replacing "
                        + old.getBankName() + " ending " + last4(old.getAccountNumber())).orElse("") + "."));
        log.info("Payout account {} approved for tenant {} by {}", account.getId(), account.getTenantId(),
                scope.userId());
        return toReview(account);
    }

    @Transactional
    public SettlementAccountReviewDto reject(UUID accountId, String reason) {
        TenantScope scope = currentScope();
        SettlementAccount account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(PaymentException.SettlementAccountNotFound::new);
        requirePending(account);
        decide(account, SettlementAccountStatus.REJECTED, scope.userId(), reason.trim());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "settlement_account.rejected", "settlement_account",
                account.getId(), account.getTenantId(), "Payout account rejected (" + account.getBankName()
                        + " ending " + last4(account.getAccountNumber()) + "): " + reason.trim()));
        return toReview(account);
    }

    // --- helpers ---

    private void decide(SettlementAccount account, SettlementAccountStatus status, UUID by, String note) {
        account.setStatus(status);
        account.setDecidedBy(by);
        account.setDecidedAt(Instant.now());
        account.setDecisionNote(note);
        accounts.saveAndFlush(account);
    }

    private void alertDirectors(SettlementAccount account, UUID submittedBy) {
        List<String> directors = identityApi.executiveDirectorEmails(account.getTenantId());
        if (directors.isEmpty()) {
            log.warn("Payout account {} submitted for tenant {} with no active Executive Director to alert",
                    account.getId(), account.getTenantId());
        }
        String companyName = companyName(account.getTenantId());
        String submitter = identityApi.emailOf(submittedBy).orElse("a member of staff");
        for (String to : directors) {
            alerts.accountSubmitted(new SettlementAlertSender.AccountSubmittedAlert(to, companyName,
                    account.getBankName(), last4(account.getAccountNumber()), submitter));
        }
    }

    private SettlementAccountReviewDto toReview(SettlementAccount account) {
        Optional<CompanyBankProfile> profile = tenancyApi.companyBankProfile(account.getTenantId());
        String registeredName = profile.map(CompanyBankProfile::registeredName).orElse(null);
        String tradingName = profile.map(CompanyBankProfile::tradingName).orElse(null);
        String declaredNumber = profile.map(CompanyBankProfile::declaredAccountNumber).orElse(null);
        SettlementAccountDto currentApproved = accounts
                .findByTenantIdAndStatus(account.getTenantId(), SettlementAccountStatus.APPROVED)
                .filter(a -> !a.getId().equals(account.getId()))
                .map(SettlementAccountService::toDto).orElse(null);
        return new SettlementAccountReviewDto(account.getTenantId(), registeredName, tradingName, toDto(account),
                SettlementAccountNames.matchesCompany(account.getAccountName(), registeredName, tradingName),
                profile.map(CompanyBankProfile::declaredBankName).orElse(null), declaredNumber,
                profile.map(CompanyBankProfile::declaredAccountName).orElse(null),
                declaredNumber == null ? null : declaredNumber.equals(account.getAccountNumber()),
                currentApproved);
    }

    private String companyName(UUID tenantId) {
        return tenancyApi.organizationNamesFor(Set.of(tenantId)).getOrDefault(tenantId, "your company");
    }

    private static void requirePending(SettlementAccount account) {
        if (account.getStatus() != SettlementAccountStatus.PENDING) {
            throw new PaymentException.SettlementAccountNotPending(account.getStatus().wire());
        }
    }

    static SettlementAccountDto toDto(SettlementAccount a) {
        return new SettlementAccountDto(a.getId(), a.getBankCode(), a.getBankName(), a.getAccountNumber(),
                a.getAccountName(), a.getCurrency().name(), a.getStatus().wire(), a.getCreatedAt(),
                a.getDecidedAt(), a.getDecisionNote());
    }

    private static String last4(String accountNumber) {
        return accountNumber.substring(accountNumber.length() - 4);
    }

    /** The company is the caller's own, and only a company-wide view may set where its money goes. */
    private static UUID companyWide(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new PaymentException.CompanyScopeRequired();
        }
        if (scope.branchId() != null) {
            throw new PaymentException.SettlementRequiresCompanyWideScope();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
