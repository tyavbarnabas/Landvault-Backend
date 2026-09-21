package com.techcomfort.landvaultbackend.identity.dto;

import java.util.List;

/**
 * The plaintext recovery codes, returned <strong>exactly once</strong> — at
 * confirmation, or at regeneration. They are stored hashed and can never be
 * retrieved again; a user who loses them must regenerate, which invalidates
 * the old set.
 */
public record RecoveryCodesResponse(List<String> recoveryCodes) {
}
