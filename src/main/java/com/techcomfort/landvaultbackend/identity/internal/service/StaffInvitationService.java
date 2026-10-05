package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DuplicateEmailException;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.CreateStaffInvitationRequest;
import com.techcomfort.landvaultbackend.identity.dto.InvitationPreviewDto;
import com.techcomfort.landvaultbackend.identity.dto.StaffInvitationDto;
import com.techcomfort.landvaultbackend.identity.internal.domain.Role;
import com.techcomfort.landvaultbackend.identity.internal.domain.StaffInvitation;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.domain.UserRole;
import com.techcomfort.landvaultbackend.identity.internal.enums.RoleScope;
import com.techcomfort.landvaultbackend.identity.internal.enums.UserStatus;
import com.techcomfort.landvaultbackend.identity.internal.exceptions.InvitationException;
import com.techcomfort.landvaultbackend.identity.internal.repository.PermissionRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.RoleRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.StaffInvitationRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRoleRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import com.techcomfort.landvaultbackend.tenancy.TenantStaffAccountRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant staff invitations (SI-1..SI-7). Staff are invited, never
 * self-registered: anyone who could register as a company's finance officer
 * could one day approve payments. See AGENTS.md, "Tenant self-service".
 * <ul>
 *   <li>Only company-wide holders of {@code portal.staff.invite} invite (SI-4
 *       v1), and never a role carrying permissions they don't hold.</li>
 *   <li>The role's {@code scope} decides whether a branch is required,
 *       forbidden or optional.</li>
 *   <li>An email that already has an account is refused, and acceptance
 *       creates a fresh user holding <em>only</em> the invited role — never
 *       {@code buyer}, whose company-wide assignment would dissolve a branch
 *       wall.</li>
 *   <li>The token is hashed at rest, single-use, time-boxed; resending
 *       replaces it. Unknown, expired, revoked and used tokens get one answer.</li>
 *   <li>A branch manager <em>requests</em> an invitation into their own
 *       branch; nothing is sent until a company-wide approver approves it.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InvitationProperties.class)
public class StaffInvitationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String EXECUTIVE_DIRECTOR = "executive_director";

    private final StaffInvitationRepository invitations;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenancyApi tenancyApi;
    private final AuditApi auditApi;
    private final InvitationDeliveryService delivery;
    private final InvitationProperties properties;
    private final AuthService authService;

    // --- SI-1, SI-2, SI-4: invite ---

    @Transactional
    public StaffInvitationDto invite(CreateStaffInvitationRequest request) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        if (!tenancyApi.isTenantActive(tenantId)) {
            throw new InvitationException.TenantNotActive();
        }

        Role role = invitableRole(request.roleCode());
        requireHoldsEverything(role);

        String branchName = branchFor(role, request.branchId(), tenantId);
        String email = normalise(request.email());
        requireNoAccount(email);
        if (invitations.existsOpenFor(tenantId, email)) {
            throw new InvitationException.AlreadyPending();
        }

        String companyName = tenancyApi.organizationNamesFor(Set.of(tenantId)).get(tenantId);
        String rawToken = newToken();
        StaffInvitation invitation = StaffInvitation.builder()
                .email(email)
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .roleId(role.getId())
                .scopedBranchId(request.branchId())
                .companyName(companyName)
                .branchName(branchName)
                .roleName(role.getName())
                .invitedBy(scope.userId())
                .tokenHash(OtpCodes.hash(rawToken))
                .expiresAt(Instant.now().plus(properties.ttl()))
                .sendCount(1)
                .lastSentAt(Instant.now())
                .build();
        invitation.setTenantId(tenantId);
        invitation = saveFlushingRace(invitation);

        deliver(invitation, rawToken);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "staff.invited", "staff_invitation", invitation.getId(), tenantId,
                email + " invited as " + role.getCode() + (branchName == null ? " (company-wide)" : " in branch '" + branchName + "'") + "."));
        log.info("Staff invitation {} created for tenant {} by {}", invitation.getId(), tenantId, scope.userId());
        return toDto(invitation, role.getCode());
    }

    /**
     * The first Executive Director of a new tenant — an invitation, not an
     * account with a password nobody can ever receive (the TODO this closes).
     * Runs in the tenant-creation transaction, so a failure rolls the tenant
     * back too. The inviter is the Super Admin who created the tenant.
     */
    @Transactional
    public void inviteFirstExecutive(TenantStaffAccountRequested event) {
        Role role = roleRepository.findByCode(EXECUTIVE_DIRECTOR).orElseThrow(() -> new IllegalStateException(
                "Seeded '" + EXECUTIVE_DIRECTOR + "' role is missing — migrations did not run."));
        String email = normalise(event.email());
        // Same label tenant creation has always used for this conflict.
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateEmailException();
        }
        String rawToken = newToken();
        StaffInvitation invitation = StaffInvitation.builder()
                .email(email)
                .firstName(event.firstName())
                .lastName(event.lastName())
                .phone(event.phone())
                .roleId(role.getId())
                .companyName(event.organizationName())
                .roleName(role.getName())
                .invitedBy(event.requestedByUserId())
                .tokenHash(OtpCodes.hash(rawToken))
                .expiresAt(Instant.now().plus(properties.ttl()))
                .sendCount(1)
                .lastSentAt(Instant.now())
                .build();
        invitation.setTenantId(event.tenantId());
        invitation = invitations.save(invitation);
        deliver(invitation, rawToken);
        auditApi.record(AuditEntryRequest.of(
                event.requestedByUserId(), "staff.invited", "staff_invitation", invitation.getId(), event.tenantId(),
                email + " invited as " + EXECUTIVE_DIRECTOR + " (company-wide) when the tenant was created."));
    }

    // --- a branch asks, head office decides ---

    /**
     * A branch manager asks for someone to be invited into their own branch.
     * No link exists until a company-wide approver approves, so nothing can be
     * accepted early. The same rules as a direct invitation apply — the
     * requester can't ask for a role carrying permissions they don't hold —
     * and the branch is always theirs, never the request's.
     */
    @Transactional
    public StaffInvitationDto request(CreateStaffInvitationRequest request) {
        TenantScope scope = currentScope();
        // Inviters invite directly — and must never request and then approve their own request.
        if (scope.tenantId() == null || scope.branchId() == null || heldAuthorities().contains("portal.staff.invite")) {
            throw new InvitationException.BranchScopeOnly();
        }
        UUID tenantId = scope.tenantId();
        if (!tenancyApi.isTenantActive(tenantId)) {
            throw new InvitationException.TenantNotActive();
        }
        Role role = invitableRole(request.roleCode());
        if (role.getScope() == RoleScope.COMPANY) {
            throw new InvitationException.ScopeMismatch(
                    "'" + role.getCode() + "' is company-wide, so only head office can invite one.");
        }
        if (request.branchId() != null && !request.branchId().equals(scope.branchId())) {
            throw new InvitationException.ScopeMismatch(
                    "Requests are always for your own branch. Leave branchId out.");
        }
        requireHoldsEverything(role);
        String branchName = tenancyApi.branchNameFor(scope.branchId(), tenantId)
                .orElseThrow(InvitationException.BranchNotFound::new);
        String email = normalise(request.email());
        requireNoAccount(email);
        if (invitations.existsOpenFor(tenantId, email)) {
            throw new InvitationException.AlreadyPending();
        }

        StaffInvitation invitation = StaffInvitation.builder()
                .email(email)
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .roleId(role.getId())
                .scopedBranchId(scope.branchId())
                .companyName(tenancyApi.organizationNamesFor(Set.of(tenantId)).get(tenantId))
                .branchName(branchName)
                .roleName(role.getName())
                .invitedBy(scope.userId())
                .approvalRequired(true)
                .expiresAt(Instant.now().plus(properties.requestTtl()))
                .sendCount(0)
                .build();
        invitation.setTenantId(tenantId);
        invitation = saveFlushingRace(invitation);

        notifyApprovers(invitation);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "staff.invitation_requested", "staff_invitation", invitation.getId(), tenantId,
                "Asked to invite " + email + " as " + role.getCode() + " in branch '" + branchName + "'."));
        return toDto(invitation, role.getCode());
    }

    /** Approval sends the ordinary invitation: a fresh link, the normal 72-hour clock. */
    @Transactional
    public StaffInvitationDto approve(UUID invitationId) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        StaffInvitation invitation = awaitingApproval(invitationId, tenantId);
        if (!invitation.getExpiresAt().isAfter(Instant.now())) {
            throw new InvitationException.RequestExpired();
        }
        if (!tenancyApi.isTenantActive(tenantId)) {
            throw new InvitationException.TenantNotActive();
        }
        Role role = roleRepository.findById(invitation.getRoleId())
                .orElseThrow(() -> new IllegalStateException("Requested role no longer exists."));
        // The approver is the one granting it now, so the rule applies to them too.
        requireHoldsEverything(role);
        // An account may have appeared with that email while the request waited.
        requireNoAccount(invitation.getEmail());

        Instant now = Instant.now();
        String rawToken = newToken();
        invitation.setTokenHash(OtpCodes.hash(rawToken));
        invitation.setExpiresAt(now.plus(properties.ttl()));
        invitation.setSendCount(1);
        invitation.setLastSentAt(now);
        invitation.setApprovedAt(now);
        invitation.setApprovedBy(scope.userId());
        invitations.saveAndFlush(invitation);
        deliver(invitation, rawToken);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "staff.invitation_approved", "staff_invitation", invitationId, tenantId,
                "Approved the request to invite " + invitation.getEmail() + " as " + role.getCode()
                        + " in branch '" + invitation.getBranchName() + "'; invitation sent."));
        return toDto(invitation, role.getCode());
    }

    @Transactional
    public StaffInvitationDto reject(UUID invitationId, String reason) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        StaffInvitation invitation = awaitingApproval(invitationId, tenantId);
        invitation.setRejectedAt(Instant.now());
        invitation.setRejectedBy(scope.userId());
        invitation.setRejectionReason(reason.trim());
        invitations.saveAndFlush(invitation);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "staff.invitation_rejected", "staff_invitation", invitationId, tenantId,
                "Rejected the request to invite " + invitation.getEmail() + ": " + reason.trim()));
        return toDto(invitation, roleCode(invitation));
    }

    /** The requester withdraws their own request. Anyone else's is a 404 — not theirs to see touched. */
    @Transactional
    public StaffInvitationDto cancel(UUID invitationId) {
        TenantScope scope = currentScope();
        if (scope.tenantId() == null) {
            throw new InvitationException.NotFound();
        }
        StaffInvitation invitation = invitations.findByIdAndTenantId(invitationId, scope.tenantId())
                .filter(i -> i.getInvitedBy().equals(scope.userId()))
                .orElseThrow(InvitationException.NotFound::new);
        if (!invitation.isAwaitingApproval()) {
            throw new InvitationException.NotAwaitingApproval();
        }
        invitation.setRevokedAt(Instant.now());
        invitation.setRevokedBy(scope.userId());
        invitations.saveAndFlush(invitation);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "staff.invitation_request_cancelled", "staff_invitation", invitationId,
                scope.tenantId(), "Withdrew the request to invite " + invitation.getEmail() + "."));
        return toDto(invitation, roleCode(invitation));
    }

    // --- listing, SI-5 revoke, SI-6 resend ---

    /** Company-wide callers see every invitation; a branch-scoped caller sees only their branch's. */
    @Transactional(readOnly = true)
    public List<StaffInvitationDto> list() {
        TenantScope scope = currentScope();
        if (scope.tenantId() == null) {
            throw new InvitationException.CompanyWideOnly();
        }
        var roleCodes = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, Role::getCode));
        List<StaffInvitation> rows = scope.branchId() == null
                ? invitations.findByTenantIdOrderByCreatedAtDesc(scope.tenantId())
                : invitations.findByTenantIdAndScopedBranchIdOrderByCreatedAtDesc(scope.tenantId(), scope.branchId());
        return rows.stream()
                .map(i -> toDto(i, roleCodes.get(i.getRoleId())))
                .toList();
    }

    @Transactional
    public StaffInvitationDto revoke(UUID invitationId) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        StaffInvitation invitation = invitations.findByIdAndTenantId(invitationId, tenantId)
                .orElseThrow(InvitationException.NotFound::new);
        if (!invitation.isOpen()) {
            throw new InvitationException.NotOpen();
        }
        invitation.setRevokedAt(Instant.now());
        invitation.setRevokedBy(scope.userId());
        invitations.saveAndFlush(invitation);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "staff.invitation_revoked", "staff_invitation", invitationId, tenantId,
                "Invitation for " + invitation.getEmail() + " revoked."));
        return toDto(invitation, roleCode(invitation));
    }

    /**
     * SI-6: a new token replaces the old one — never two live links — and the
     * expiry starts again. Rate-limited per invitation so the endpoint can't
     * be used to flood an inbox. An expired, still-open invitation can be
     * resent; an accepted or revoked one can't.
     */
    @Transactional
    public StaffInvitationDto resend(UUID invitationId) {
        TenantScope scope = currentScope();
        UUID tenantId = requireCompanyWide(scope);
        StaffInvitation invitation = invitations.findByIdAndTenantId(invitationId, tenantId)
                .orElseThrow(InvitationException.NotFound::new);
        if (!invitation.isOpen()) {
            throw new InvitationException.NotOpen();
        }
        if (invitation.isAwaitingApproval()) {
            throw new InvitationException.AwaitingApproval();
        }
        if (!tenancyApi.isTenantActive(tenantId)) {
            throw new InvitationException.TenantNotActive();
        }
        Instant now = Instant.now();
        if (invitation.getSendCount() >= properties.maxSends()) {
            throw new InvitationException.ResendLimitReached();
        }
        if (invitation.getLastSentAt().plus(properties.resendMinInterval()).isAfter(now)) {
            throw new InvitationException.ResendTooSoon();
        }
        String rawToken = newToken();
        invitation.setTokenHash(OtpCodes.hash(rawToken));
        invitation.setExpiresAt(now.plus(properties.ttl()));
        invitation.setSendCount(invitation.getSendCount() + 1);
        invitation.setLastSentAt(now);
        invitations.saveAndFlush(invitation);
        deliver(invitation, rawToken);
        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "staff.invitation_resent", "staff_invitation", invitationId, tenantId,
                "Invitation for " + invitation.getEmail() + " resent (send " + invitation.getSendCount()
                        + "); the previous link no longer works."));
        return toDto(invitation, roleCode(invitation));
    }

    // --- SI-3: preview and accept (public) ---

    @Transactional(readOnly = true)
    public InvitationPreviewDto preview(String rawToken) {
        StaffInvitation invitation = invitations.findByTokenHash(OtpCodes.hash(rawToken))
                .filter(i -> i.isUsableAt(Instant.now()))
                .orElseThrow(InvitationException.Invalid::new);
        return new InvitationPreviewDto(invitation.getEmail(), invitation.getFirstName(), invitation.getCompanyName(),
                invitation.getRoleName(), invitation.getBranchName(), invitation.getExpiresAt());
    }

    /**
     * Creates the account from the invitation — tenant, branch and role all
     * from the invitation, never the request — signs the person in, and
     * closes the invitation. The row is locked, so one link can't create two
     * accounts. Following the link proves control of the mailbox, so the
     * account starts ACTIVE with the email verified.
     */
    @Transactional
    public IssuedSession accept(String rawToken, String password) {
        StaffInvitation invitation = invitations.findByTokenHashForUpdate(OtpCodes.hash(rawToken))
                .filter(i -> i.isUsableAt(Instant.now()))
                .orElseThrow(InvitationException.Invalid::new);
        if (!tenancyApi.isTenantActive(invitation.getTenantId())) {
            throw new InvitationException.TenantNotActive();
        }
        requireNoAccount(invitation.getEmail());
        Role role = roleRepository.findById(invitation.getRoleId())
                .orElseThrow(() -> new IllegalStateException("Invited role no longer exists."));

        Instant now = Instant.now();
        User user = User.builder()
                .firstName(invitation.getFirstName())
                .lastName(invitation.getLastName())
                .email(invitation.getEmail())
                .phone(invitation.getPhone())
                // NOT NULL on every account; meaningless for staff. Same
                // default as SuperAdminBootstrap.
                .country("NG")
                .currency(Currency.NGN)
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(now)
                .twoFaEnabled(false)
                .mustChangePassword(false)
                .passwordHash(passwordEncoder.encode(password))
                .build();
        user.setTenantId(invitation.getTenantId());
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new InvitationException.EmailHasAccount();
        }
        // Only the invited role. Never buyer.
        UserRole assignment = userRoleRepository.save(UserRole.builder()
                .user(user)
                .roleId(role.getId())
                .scopedBranchId(invitation.getScopedBranchId())
                .build());

        invitation.setAcceptedAt(now);
        invitation.setAcceptedUserId(user.getId());
        invitations.saveAndFlush(invitation);
        auditApi.record(AuditEntryRequest.of(
                user.getId(), "staff.invitation_accepted", "staff_invitation", invitation.getId(),
                invitation.getTenantId(), invitation.getEmail() + " joined as " + role.getCode()
                        + (invitation.getBranchName() == null ? " (company-wide)." : " in branch '" + invitation.getBranchName() + "'.")));
        log.info("Invitation {} accepted; user {} created for tenant {}", invitation.getId(), user.getId(),
                invitation.getTenantId());
        return authService.issueAuthResponse(user, List.of(assignment));
    }

    // --- rules ---

    private Role invitableRole(String roleCode) {
        return roleRepository.findByCode(roleCode.trim())
                .filter(r -> r.getScope() != null)
                .orElseThrow(() -> new InvitationException.RoleNotInvitable(roleCode));
    }

    private StaffInvitation awaitingApproval(UUID invitationId, UUID tenantId) {
        StaffInvitation invitation = invitations.findByIdAndTenantId(invitationId, tenantId)
                .orElseThrow(InvitationException.NotFound::new);
        if (!invitation.isAwaitingApproval()) {
            throw new InvitationException.NotAwaitingApproval();
        }
        return invitation;
    }

    /**
     * Emails every active company-wide Executive Director. If there is none,
     * the request still stands and shows in the list — logged, not failed.
     */
    private void notifyApprovers(StaffInvitation invitation) {
        String requesterName = userRepository.findById(invitation.getInvitedBy())
                .map(u -> u.getFirstName() + " " + u.getLastName())
                .orElse("A branch manager");
        List<User> approvers = userRepository.findCompanyWideHolders(
                invitation.getTenantId(), EXECUTIVE_DIRECTOR, UserStatus.ACTIVE);
        if (approvers.isEmpty()) {
            log.warn("Invitation request {} has no active approver to notify in tenant {}",
                    invitation.getId(), invitation.getTenantId());
        }
        for (User approver : approvers) {
            delivery.sendApprovalRequest(new InvitationDeliveryService.ApprovalRequestEmail(
                    approver.getEmail(), approver.getFirstName(), requesterName, invitation.getBranchName(),
                    invitation.getFirstName() + " " + invitation.getLastName(), invitation.getEmail(),
                    invitation.getRoleName(), properties.reviewUrl(), invitation.getExpiresAt()));
        }
    }

    /** SI-4: the inviter must already hold every permission the role carries. */
    private void requireHoldsEverything(Role role) {
        Set<String> held = heldAuthorities();
        List<String> carried = permissionRepository.findCodesByRoleIdIn(List.of(role.getId()));
        if (!held.containsAll(carried)) {
            throw new InvitationException.CannotGrant(role.getCode());
        }
    }

    private static Set<String> heldAuthorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /** The role's scope decides: a branch is required, forbidden or optional — and must be the company's own. */
    private String branchFor(Role role, UUID branchId, UUID tenantId) {
        if (role.getScope() == RoleScope.COMPANY && branchId != null) {
            throw new InvitationException.ScopeMismatch(
                    "'" + role.getCode() + "' is company-wide and can't be limited to a branch. Leave branchId out.");
        }
        if (role.getScope() == RoleScope.BRANCH && branchId == null) {
            throw new InvitationException.ScopeMismatch(
                    "'" + role.getCode() + "' runs a branch, so branchId is required.");
        }
        if (branchId == null) {
            return null;
        }
        return tenancyApi.branchNameFor(branchId, tenantId).orElseThrow(InvitationException.BranchNotFound::new);
    }

    private void requireNoAccount(String email) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new InvitationException.EmailHasAccount();
        }
    }

    private StaffInvitation saveFlushingRace(StaffInvitation invitation) {
        try {
            return invitations.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException e) {
            // uq_staff_invitations_open_email: a concurrent invite for the same person.
            throw new InvitationException.AlreadyPending();
        }
    }

    private void deliver(StaffInvitation invitation, String rawToken) {
        delivery.send(new InvitationDeliveryService.InvitationEmail(
                invitation.getEmail(), invitation.getFirstName(), invitation.getCompanyName(),
                invitation.getRoleName(), invitation.getBranchName(),
                properties.acceptUrl() + "#token=" + rawToken, invitation.getExpiresAt()));
    }

    private static UUID requireCompanyWide(TenantScope scope) {
        if (scope.tenantId() == null || scope.branchId() != null) {
            throw new InvitationException.CompanyWideOnly();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String roleCode(StaffInvitation invitation) {
        return roleRepository.findById(invitation.getRoleId()).map(Role::getCode).orElse(null);
    }

    private static StaffInvitationDto toDto(StaffInvitation i, String roleCode) {
        boolean lapsed = !i.getExpiresAt().isAfter(Instant.now());
        String status = i.getAcceptedAt() != null ? "accepted"
                : i.getRevokedAt() != null ? "revoked"
                : i.getRejectedAt() != null ? "rejected"
                : lapsed ? "expired"
                : i.isAwaitingApproval() ? "awaiting_approval" : "pending";
        return new StaffInvitationDto(i.getId(), i.getEmail(), i.getFirstName(), i.getLastName(), roleCode,
                i.getRoleName(), i.getScopedBranchId(), i.getBranchName(), status, i.getCreatedAt(),
                i.getExpiresAt(), i.getAcceptedAt(), i.getRevokedAt(), i.getSendCount(), i.isApprovalRequired(),
                i.getInvitedBy(), i.getApprovedAt(), i.getRejectedAt(), i.getRejectionReason());
    }
}
