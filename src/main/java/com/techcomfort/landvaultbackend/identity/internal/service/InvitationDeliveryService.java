package com.techcomfort.landvaultbackend.identity.internal.service;

import java.time.Instant;

/**
 * Delivers an invitation link. A sibling of {@link OtpDeliveryService} rather
 * than a use of it: that interface is shaped for a six-digit code, and an
 * invitation is a link with different words. Same two implementations, same
 * switch, same rule — only the test-only logging one may ever write a link
 * to the log.
 */
public interface InvitationDeliveryService {

    record InvitationEmail(String to, String firstName, String companyName, String roleName, String branchName,
                           String link, Instant expiresAt) {
    }

    /** Tells a company-wide approver a branch manager is waiting. Carries no credential. */
    record ApprovalRequestEmail(String to, String approverFirstName, String requesterName, String branchName,
                                String inviteeName, String inviteeEmail, String roleName, String reviewUrl,
                                Instant expiresAt) {
    }

    void send(InvitationEmail email);

    void sendApprovalRequest(ApprovalRequestEmail email);
}
