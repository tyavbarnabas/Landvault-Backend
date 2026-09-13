package com.techcomfort.landvaultbackend.tenancy.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type of a company-level verification document (CAC certificate, TIN,
 * SCUML certificate, ...). Matches the frontend's {@code DocumentType}
 * union in {@code tenantsService.ts} exactly — named
 * {@code OrganizationDocumentType} here (not {@code DocumentType}) to keep
 * it distinct from land title documents (C of O, R of O, Governor's
 * Consent, Gazette, survey plan), which the frontend deliberately keeps
 * out of this type: title evidence is per-estate, not per-company, and
 * belongs to the future {@code documents} module instead.
 * <p>
 * {@link #value} is the lowercase wire value; see AGENTS.md for the enum
 * JSON-mapping strategy.
 */
public enum OrganizationDocumentType {

    CAC_CERTIFICATE("cac_certificate"),
    CAC_STATUS_REPORT("cac_status_report"),
    TIN("tin"),
    PROOF_OF_ADDRESS("proof_of_address"),
    SCUML_CERTIFICATE("scuml_certificate"),
    STATE_REGULATOR_PERMIT("state_regulator_permit"),
    REDAN_CERTIFICATE("redan_certificate"),
    OTHER("other");

    private final String value;

    OrganizationDocumentType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static OrganizationDocumentType fromValue(String value) {
        for (OrganizationDocumentType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown OrganizationDocumentType: " + value);
    }
}
