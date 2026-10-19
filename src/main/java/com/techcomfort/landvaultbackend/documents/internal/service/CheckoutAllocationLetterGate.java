package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.checkout.AllocationLetterGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Checkout's question, answered by the letter approval rules (AL). Its own bean, like {@code PaymentReceiptLookup}. */
@Service
@RequiredArgsConstructor
public class CheckoutAllocationLetterGate implements AllocationLetterGate {

    private final LetterApprovalService approvals;

    @Override
    public boolean letterReady(UUID estateId) {
        return approvals.issuable(estateId).isPresent();
    }
}
