package com.techcomfort.landvaultbackend.identity.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.identity.dto.AcceptInvitationRequest;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.InvitationPreviewDto;
import com.techcomfort.landvaultbackend.identity.dto.InvitationTokenRequest;
import com.techcomfort.landvaultbackend.identity.internal.security.RefreshTokenCookies;
import com.techcomfort.landvaultbackend.identity.internal.service.IssuedSession;
import com.techcomfort.landvaultbackend.identity.internal.service.StaffInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SI-3: the invited person, who has no account yet. Public — listed one route
 * at a time in SecurityConfig. The token comes in the body (the page reads it
 * from the link's #fragment), never a URL.
 */
@RestController
@RequestMapping("/api/auth/invitations")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_AUTH)
@SecurityRequirements
public class InvitationAcceptController {

    private final StaffInvitationService service;
    private final RefreshTokenCookies refreshTokenCookies;

    @Operation(summary = "See an invitation before accepting it",
            description = """
                    Public. Who invited you, as what, for which branch — so the page can say so before \
                    you set a password. An unknown, expired, revoked or already-used link all return \
                    the same `INVITATION_INVALID`: telling them apart would reveal which links existed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The invitation"),
            @ApiResponse(responseCode = "400", description = "`INVITATION_INVALID`", content = @Content())
    })
    @PostMapping("/preview")
    public ResponseEntity<InvitationPreviewDto> preview(@Valid @RequestBody InvitationTokenRequest request) {
        return ResponseEntity.ok(service.preview(request.token()));
    }

    @Operation(summary = "Accept an invitation and set your password",
            description = """
                    Public. Creates your account with the company, role and branch **from the \
                    invitation** — never from this request — signs you in (an access token in the body, \
                    the refresh token as an HttpOnly cookie, exactly like login), and closes the \
                    invitation: the link never works again.

                    Refused with `TENANT_NOT_ACTIVE` while the company is suspended, and \
                    `EMAIL_HAS_ACCOUNT` if that email has registered in the meantime.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account created; signed in"),
            @ApiResponse(responseCode = "400", description = "`INVITATION_INVALID`, or a blank password", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`TENANT_NOT_ACTIVE`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`EMAIL_HAS_ACCOUNT`", content = @Content())
    })
    @PostMapping("/accept")
    public ResponseEntity<AuthResponse> accept(@Valid @RequestBody AcceptInvitationRequest request) {
        IssuedSession session = service.accept(request.token(), request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(session.refreshToken()).toString())
                .body(session.response());
    }
}
