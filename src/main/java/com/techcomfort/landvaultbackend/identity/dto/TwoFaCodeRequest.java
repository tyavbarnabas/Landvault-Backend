package com.techcomfort.landvaultbackend.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A bare code, for the endpoints that need one to prove possession:
 * {@code /2fa/confirm}, {@code /2fa/disable} and
 * {@code /2fa/recovery-codes/regenerate}.
 * <p>
 * For disable, this may be a TOTP code <em>or</em> a recovery code — a
 * session alone is deliberately not enough, since a hijacked session could
 * otherwise strip the very protection 2FA exists to provide.
 */
public record TwoFaCodeRequest(@NotBlank String code) {
}
