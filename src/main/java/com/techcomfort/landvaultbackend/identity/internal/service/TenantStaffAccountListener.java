package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.tenancy.TenantStaffAccountRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Responds to {@link TenantStaffAccountRequested} — see that event's Javadoc
 * for why this is event-driven. Since SI-3 it sends the new tenant's first
 * Executive Director an <strong>invitation</strong> rather than creating an
 * account with a random password nobody could ever receive (the TODO this
 * listener used to carry). {@code propagation = MANDATORY}: it must run in the
 * tenant-creation transaction, so a failure rolls the tenant back too.
 */
@Component
@RequiredArgsConstructor
public class TenantStaffAccountListener {

    private final StaffInvitationService invitations;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTenantStaffAccountRequested(TenantStaffAccountRequested event) {
        invitations.inviteFirstExecutive(event);
    }
}
