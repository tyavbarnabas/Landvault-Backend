package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.checkout.internal.repository.ReservationRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionRepository;
import com.techcomfort.landvaultbackend.inventory.PlotHistoryProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Any reservation or transaction at all, in any status — an expired hold is
 * still history. Neither table is RLS-policied (buyer-owned rows), so a plain
 * repository read sees them from a tenant's request.
 */
@Component
@RequiredArgsConstructor
class CheckoutPlotHistoryProbe implements PlotHistoryProbe {

    private final ReservationRepository reservations;
    private final TransactionRepository transactions;

    @Override
    @Transactional(readOnly = true)
    public boolean hasPurchaseHistory(UUID plotId) {
        return reservations.existsByPlotId(plotId) || transactions.existsByPlotId(plotId);
    }
}
