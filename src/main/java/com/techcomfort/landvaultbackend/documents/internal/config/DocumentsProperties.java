package com.techcomfort.landvaultbackend.documents.internal.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code landvault.documents.*}: issued documents and their public verify page. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "landvault.documents")
public class DocumentsProperties {

    /**
     * The frontend's verify page; a document's QR code opens
     * {@code {verifyUrl}/{code}}. Printed on every document, so it must be the
     * public address, never an internal one.
     */
    private String verifyUrl;

    /**
     * Verify lookups one address may make per window. A security parameter:
     * the codes are 64 random bits, and this is what makes guessing them
     * pointless rather than merely slow.
     */
    private int verifyRequestsPerWindow = 30;

    private Duration verifyWindow = Duration.ofMinutes(1);

    /** Bound on tracked addresses; see MarketplaceRateLimitProperties for the same reasoning. */
    private int maxTrackedClients = 100_000;

    /** {@link #verifyUrl} for one code, with no trailing-slash surprises. */
    public String verifyUrlFor(String code) {
        String base = verifyUrl.endsWith("/") ? verifyUrl.substring(0, verifyUrl.length() - 1) : verifyUrl;
        return base + "/" + code;
    }
}
