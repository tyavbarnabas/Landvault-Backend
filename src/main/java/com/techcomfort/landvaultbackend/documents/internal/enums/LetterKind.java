package com.techcomfort.landvaultbackend.documents.internal.enums;

/** Which allocation letter (changeset 101): one of each per sale at most. */
public enum LetterKind {
    /** The final letter: paid in full and verified (HT-ALC-…). */
    ALLOCATION,
    /** An instalment plan's deposit verified: held on the plan until the last instalment (HT-PAL-…). */
    PROVISIONAL
}
