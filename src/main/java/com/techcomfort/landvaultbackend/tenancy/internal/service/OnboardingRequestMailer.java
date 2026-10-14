package com.techcomfort.landvaultbackend.tenancy.internal.service;

/** Emails about a new onboarding request: HomeTrue's alert and the company's confirmation. */
public interface OnboardingRequestMailer {

    /** To HomeTrue: everything the company sent. */
    void requestReceived(RequestReceivedAlert alert);

    /**
     * To the address typed into the form. Deliberately carries none of the
     * submitted text: anyone can type anyone's email, so echoing their words
     * would let a stranger send arbitrary text from HomeTrue to that inbox.
     */
    void confirmation(String to);

    record RequestReceivedAlert(
            String to,
            String companyName,
            String rcNumber,
            String companyAddress,
            String statesOfOperation,
            Integer estimatedEstateCount,
            String contactName,
            String contactRole,
            String contactEmail,
            String contactPhone,
            String preferredContactMethod,
            String preferredContactTime,
            String message
    ) {
    }
}
