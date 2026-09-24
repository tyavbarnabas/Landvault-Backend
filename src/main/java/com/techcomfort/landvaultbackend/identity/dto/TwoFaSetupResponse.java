package com.techcomfort.landvaultbackend.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code POST /api/auth/2fa/setup} response. 2FA is <strong>not</strong>
 * enabled at this point — the user must prove their app holds the secret via
 * {@code /2fa/confirm} first. See AGENTS.md.
 * <p>
 * {@code secret} is returned so a client can offer manual entry when a QR
 * code can't be scanned; it is the same value encoded in {@code otpAuthUri}.
 * This is the only response that ever carries it.
 */
@Schema(name = "TwoFaSetupResponse",
        description = "Enrolment material. **Two-factor is still OFF after this call** — only "
                + "`POST /api/auth/2fa/confirm` switches it on.")
public record TwoFaSetupResponse(
        @Schema(description = "The TOTP shared secret, in base32. **Returned here and nowhere "
                + "else**, so a client can offer manual entry when a QR code cannot be scanned. "
                + "It is encrypted at rest (AES-256-GCM) and is never readable again through any "
                + "endpoint. Treat it like a password: never log it, never persist it client-side.")
        String secret,
        @Schema(description = "The `otpauth://` URI to render as a QR code. Contains the same "
                + "secret.", example = "otpauth://totp/LandVault:ada@example.com?secret=...&issuer=LandVault")
        String otpAuthUri) {
}
