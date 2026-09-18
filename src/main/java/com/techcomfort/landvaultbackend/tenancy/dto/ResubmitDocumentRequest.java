package com.techcomfort.landvaultbackend.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /api/admin/tenants/{id}/documents/{documentId}/resubmit} body
 * — matches the frontend's {@code { fileName, size }} call exactly (no real
 * file storage integration exists yet, same TODO as
 * {@code OrganizationDocument.storageKey}; only metadata is replaced here).
 * Resets the document's {@code status} to {@code PENDING} — does NOT itself
 * advance the organization's {@code verificationState}; see AGENTS.md.
 */
public record ResubmitDocumentRequest(
        @NotBlank String fileName,
        @Positive long size
) {
}
