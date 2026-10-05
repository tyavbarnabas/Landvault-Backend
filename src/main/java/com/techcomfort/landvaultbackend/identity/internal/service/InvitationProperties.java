package com.techcomfort.landvaultbackend.identity.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code landvault.invitations.*}. {@code acceptUrl} is the frontend page the
 * link opens; the token goes after {@code #} so it never reaches a server log
 * or a referrer. The resend limits are security parameters: they stop the
 * endpoint being a way to flood an inbox. {@code requestTtl} is how long a
 * branch manager's request waits for approval; {@code reviewUrl} is the portal
 * page the approver's email points to. See AGENTS.md.
 */
@ConfigurationProperties(prefix = "landvault.invitations")
public record InvitationProperties(
        String delivery,
        String fromAddress,
        String acceptUrl,
        Duration ttl,
        Duration resendMinInterval,
        int maxSends,
        Duration requestTtl,
        String reviewUrl
) {
}
