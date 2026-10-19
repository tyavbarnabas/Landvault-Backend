package com.techcomfort.landvaultbackend.inventory;

import java.util.Optional;
import java.util.UUID;

/** Estate facts for the documents module's generated letters. */
public interface EstateDocumentsApi {

    /**
     * One estate's facts, read under the caller's RLS scope: empty when the
     * estate doesn't exist or isn't the caller's company's (or, for branch
     * staff, their branch's or the company's).
     */
    Optional<EstateDocumentFacts> forDocuments(UUID estateId);

    /** One plot as a letter names it, read under the caller's RLS scope like {@link #forDocuments}. */
    Optional<PlotDocumentFacts> plotForDocuments(UUID plotId);
}
