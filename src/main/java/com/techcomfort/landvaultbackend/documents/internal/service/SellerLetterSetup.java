package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentsApi;
import com.techcomfort.landvaultbackend.inventory.PlotDocumentFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads an estate's approved letter setup for an offer issued during a
 * BUYER's request. A buyer has no tenant scope, and the setup lives in
 * RLS-policied tables, so this runs in its own read-only transaction scoped
 * to the selling company alone — set before that transaction begins (the
 * {@code SET LOCAL}s are taken from it) and restored afterwards, the
 * webhook's pattern. The buyer's own transaction never gains the scope.
 */
@Component
@RequiredArgsConstructor
public class SellerLetterSetup {

    private final Reader reader;

    public record Setup(LetterApprovalService.IssuableLetter letter, PlotDocumentFacts plot) {
    }

    public Optional<Setup> read(UUID sellerTenantId, UUID actorUserId, UUID estateId, UUID plotId) {
        Optional<TenantScope> previous = TenantContext.get();
        TenantContext.set(new TenantScope(actorUserId, sellerTenantId, null, false));
        try {
            return reader.read(estateId, plotId);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /** A separate bean so the new transaction is real (a self-call would bypass the proxy). */
    @Component
    @RequiredArgsConstructor
    static class Reader {

        private final LetterApprovalService approvals;
        private final EstateDocumentsApi estates;

        @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
        public Optional<Setup> read(UUID estateId, UUID plotId) {
            return approvals.issuable(estateId)
                    .flatMap(letter -> estates.plotForDocuments(plotId).map(plot -> new Setup(letter, plot)));
        }
    }
}
