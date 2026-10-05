package com.techcomfort.landvaultbackend.identity.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * <strong>Tests and CI only.</strong> Writes the invitation link — a live
 * credential into a company — to the log, so the suite can read it without a
 * mail server. Selecting this anywhere real would let anyone with log access
 * join a company as whatever role was invited. Never the default.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "landvault.invitations.delivery", havingValue = "log")
public class LoggingInvitationDeliveryService implements InvitationDeliveryService {

    @Override
    public void send(InvitationEmail email) {
        log.info("[TEST-ONLY invitation delivery] to={} link={}", email.to(), email.link());
    }

    @Override
    public void sendApprovalRequest(ApprovalRequestEmail email) {
        log.info("[TEST-ONLY approval request] to={} invitee={}", email.to(), email.inviteeEmail());
    }
}
