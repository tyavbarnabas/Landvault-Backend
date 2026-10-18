package com.techcomfort.landvaultbackend.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * What a letterhead prints about the company and its offices (documents, DS-1).
 * The address is the head office: the operating address if one was given,
 * otherwise the registered one. Any field may be null when it wasn't captured.
 *
 * @param branches the branches the caller can see, under their own RLS scope
 */
public record CompanyLetterheadDetails(String registeredName, String rcNumber, String address, String phone,
                                       String email, String website, List<BranchOffice> branches) {

    /** A branch and its office (changeset 066). Fields are null when the branch hasn't entered them. */
    public record BranchOffice(UUID id, String name, String address, String phone, String email) {
    }
}
