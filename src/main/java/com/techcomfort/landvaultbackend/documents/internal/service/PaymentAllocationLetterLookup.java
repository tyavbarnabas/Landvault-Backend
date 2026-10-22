package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.documents.internal.repository.AllocationLetterRepository;
import com.techcomfort.landvaultbackend.payments.AllocationLetterLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Payments' {@link AllocationLetterLookup}. Its own bean depending only on the repository, like {@code PaymentReceiptLookup}. */
@Service
@RequiredArgsConstructor
public class PaymentAllocationLetterLookup implements AllocationLetterLookup {

    private final AllocationLetterRepository letters;

    @Override
    @Transactional(readOnly = true)
    public Optional<LetterRef> letterFor(UUID transactionId) {
        // The newest: the provisional letter after a deposit, the final one once paid in full.
        return letters.findByTransactionIdOrderByIssuedAtDesc(transactionId).stream().findFirst()
                .map(l -> new LetterRef(l.getId(), l.getNumber()));
    }
}
