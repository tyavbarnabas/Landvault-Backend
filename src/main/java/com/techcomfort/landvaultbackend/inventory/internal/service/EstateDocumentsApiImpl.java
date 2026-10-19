package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentsApi;
import com.techcomfort.landvaultbackend.inventory.PlotDocumentFacts;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateFee;
import com.techcomfort.landvaultbackend.inventory.internal.repository.BlockRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateDefaultTermsRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateFeeRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRefundTermsRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain repository reads under the caller's RLS — deliberately not a definer
 * function: only the estate's own company (and, for branch staff, the EB-2
 * visibility rule) may read its facts for a letter.
 */
@Service
@RequiredArgsConstructor
public class EstateDocumentsApiImpl implements EstateDocumentsApi {

    private final EstateRepository estates;
    private final EstateFeeRepository fees;
    private final EstateRefundTermsRepository refundTerms;
    private final EstateDefaultTermsRepository defaultTerms;
    private final PlotRepository plots;
    private final BlockRepository blocks;

    @Override
    @Transactional(readOnly = true)
    public Optional<EstateDocumentFacts> forDocuments(UUID estateId) {
        return estates.findById(estateId).map(estate -> new EstateDocumentFacts(
                estate.getId(), estate.getTenantId(), estate.getBranchId(), estate.getName(), estate.getArea(),
                estate.getCity(), estate.getState(), fees(estate), refundTerms(estateId), defaultTerms(estateId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlotDocumentFacts> plotForDocuments(UUID plotId) {
        return plots.findById(plotId).map(plot -> new PlotDocumentFacts(plot.getId(), plot.getEstateId(),
                plot.getBlockId() == null ? null : blocks.findById(plot.getBlockId()).map(Block::getName).orElse(null),
                plot.getPlotNumber(), plot.getNominalSizeSqm(), Boolean.TRUE.equals(plot.getIsCorner())));
    }

    /** Null when no schedule was ever declared; "nothing to declare" is an empty list. */
    private EstateDocumentFacts.Fees fees(Estate estate) {
        if (estate.getFeesDeclaredAt() == null) {
            return null;
        }
        List<EstateDocumentFacts.Fee> items = fees
                .findByEstateIdAndVersionOrderByFeeTypeAsc(estate.getId(), estate.getFeesVersion()).stream()
                .map(EstateDocumentsApiImpl::fee)
                .toList();
        return new EstateDocumentFacts.Fees(estate.getFeesVersion(), items);
    }

    private static EstateDocumentFacts.Fee fee(EstateFee fee) {
        return new EstateDocumentFacts.Fee(fee.getFeeType(), fee.getLabel(), fee.getAmount(), fee.getAmountMin(),
                fee.getAmountMax(), fee.getCurrency(), Boolean.TRUE.equals(fee.getIsFixed()),
                fee.getVariationBasis(), fee.getDueTrigger(), Boolean.TRUE.equals(fee.getRefundable()),
                Boolean.TRUE.equals(fee.getIsMandatory()));
    }

    private EstateDocumentFacts.RefundTerms refundTerms(UUID estateId) {
        return refundTerms.findFirstByEstateIdOrderByVersionDesc(estateId).map(terms ->
                new EstateDocumentFacts.RefundTerms(terms.getVersion(), terms.getDeductionPct(),
                        terms.getProcessingDays(), terms.getAppliesTo(),
                        terms.getNonRefundableFeeTypes() == null ? List.of()
                                : Arrays.stream(terms.getNonRefundableFeeTypes()).map(FeeType::valueOf).toList()))
                .orElse(null);
    }

    private EstateDocumentFacts.DefaultTerms defaultTerms(UUID estateId) {
        return defaultTerms.findFirstByEstateIdOrderByVersionDesc(estateId).map(terms ->
                new EstateDocumentFacts.DefaultTerms(terms.getVersion(), terms.getRevocationTrigger(),
                        terms.getRevocationNoticeDays(), terms.getOnRevocationRefund(),
                        terms.getDevelopmentDeadlineMonths(), Boolean.TRUE.equals(terms.getTransferRequiresConsent())))
                .orElse(null);
    }
}
