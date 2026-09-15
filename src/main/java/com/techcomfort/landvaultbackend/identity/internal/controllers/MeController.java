package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.identity.dto.MeResponse;
import com.techcomfort.landvaultbackend.identity.internal.security.AccessTokenClaims;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Not part of the task's named deliverables — a minimal, deliberately
 * outside-{@code /api/auth/**} pair of endpoints so the slice's own
 * verification steps (call a protected endpoint; confirm 401/403; prove
 * {@code @PreAuthorize} actually works) have something real to call
 * against. {@code /api/auth/**} itself is entirely public, so it can't
 * serve that purpose.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    @GetMapping
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AccessTokenClaims claims) {
        return ResponseEntity.ok(new MeResponse(
                claims.userId(), claims.email(), claims.tenantId(), claims.platformStaff(), claims.permissions()));
    }

    // Exists only to prove @EnableMethodSecurity + @PreAuthorize work end
    // to end — a buyer's token has no admin.* permissions, so this 403s.
    @GetMapping("/admin-check")
    @PreAuthorize("hasAuthority('admin.tenants.view')")
    public ResponseEntity<Void> adminCheck() {
        return ResponseEntity.ok().build();
    }
}
