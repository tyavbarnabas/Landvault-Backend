package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.inventory.dto.DeclareDefaultTermsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.DeclareFeesRequest;
import com.techcomfort.landvaultbackend.inventory.dto.DeclarePaymentPlansRequest;
import com.techcomfort.landvaultbackend.inventory.dto.PaymentPlansDto;
import com.techcomfort.landvaultbackend.inventory.dto.DeclareRefundTermsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.DefaultTermsDto;
import com.techcomfort.landvaultbackend.inventory.dto.FeeDto;
import com.techcomfort.landvaultbackend.inventory.dto.FeeScheduleDto;
import com.techcomfort.landvaultbackend.inventory.dto.PenaltyTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.RefundTermsDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateDefaultPenaltyTier;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateDefaultTerms;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateFee;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstatePaymentPlan;
import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateRefundTerms;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateDefaultPenaltyTierRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateDefaultTermsRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateFeeRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstatePaymentPlanRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PriceTierRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRefundTermsRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Declaring what a plot really costs, and what leaving costs.
 * <p>
 * The point of this service is <strong>sequence</strong>. Both of the real
 * allocation letters this was modelled on disclosed every charge — in a
 * document issued after the buyer had already paid. Nothing here judges an
 * amount, caps one, or warns about one; it requires only that the numbers
 * exist before a listing can go live.
 * <p>
 * Every declaration writes a <strong>new version</strong> rather than editing
 * the last one (FD-6). A schedule that can be quietly revised after a buyer
 * has seen it is not a disclosure, and a future acknowledgement has to be
 * able to name the version it refers to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstateDisclosureService {

    private final EstateRepository estateRepository;
    private final EstateFeeRepository feeRepository;
    private final EstateRefundTermsRepository refundTermsRepository;
    private final EstateDefaultTermsRepository defaultTermsRepository;
    private final EstateDefaultPenaltyTierRepository penaltyTierRepository;
    private final EstatePaymentPlanRepository paymentPlanRepository;
    private final PriceTierRepository priceTierRepository;
    private final AuditApi auditApi;

    // ------------------------------------------------------------------
    // Fees
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public FeeScheduleDto fees(UUID estateId) {
        Estate estate = requireEstate(estateId);
        List<EstateFee> fees = feeRepository
                .findByEstateIdAndVersionOrderByFeeTypeAsc(estateId, estate.getFeesVersion());
        return new FeeScheduleDto(
                estate.getFeesVersion(), estate.getFeesDeclaredAt(), fees.stream().map(this::toDto).toList());
    }

    /**
     * Replaces the schedule with a new version.
     * <p>
     * <strong>An empty list is a valid declaration</strong>: it states that
     * there are no charges beyond the land price, and it unblocks
     * publication. Sending nothing at all does not — silence and "nothing to
     * declare" are different facts, and only one of them is a disclosure.
     */
    @Transactional
    public FeeScheduleDto declareFees(UUID estateId, DeclareFeesRequest request) {
        Estate estate = requireEstate(estateId);
        EstateWriteAccess.requireWritable(estate);
        List<EstateFee> rows = validateAndBuild(estate, request);

        int version = estate.getFeesVersion() + 1;
        rows.forEach(fee -> fee.setVersion(version));
        feeRepository.saveAll(rows);

        estate.setFeesVersion(version);
        estate.setFeesDeclaredAt(Instant.now());
        estateRepository.save(estate);

        log.info("Fee schedule v{} declared for estate {} ({} fee(s)) by actor {}",
                version, estateId, rows.size(), actorId());
        auditApi.record(AuditEntryRequest.of(
                actorId(), "estate.fees_declared", "estate", estateId, estate.getTenantId(),
                "Fee schedule version " + version + " declared with " + rows.size() + " charge(s)"));

        return new FeeScheduleDto(
                version, estate.getFeesDeclaredAt(), rows.stream().map(this::toDto).toList());
    }

    private List<EstateFee> validateAndBuild(Estate estate, DeclareFeesRequest request) {
        List<EstateFee> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (DeclareFeesRequest.FeeDeclaration declaration : request.fees()) {
            FeeType type = FeeType.fromValue(declaration.feeType());
            DueTrigger trigger = DueTrigger.fromValue(declaration.dueTrigger());
            Currency currency = Currency.valueOf(declaration.currency().toUpperCase());
            boolean fixed = Boolean.TRUE.equals(declaration.isFixed());

            String label = declaration.label() == null || declaration.label().isBlank()
                    ? null : declaration.label().trim();
            if (type == FeeType.OTHER && label == null) {
                throw new InventoryException.InvalidRequest(
                        "A fee of type 'other' needs a label — an unnamed charge is a number, not a "
                                + "disclosure.");
            }
            if (!seen.add(type.name() + "|" + label)) {
                throw new InventoryException.InvalidRequest(
                        "Duplicate fee declared: " + type.getValue()
                                + (label == null ? "" : " (" + label + ")") + ".");
            }

            if (fixed) {
                if (declaration.amount() == null) {
                    throw new InventoryException.InvalidRequest(
                            "A fixed fee needs an amount: " + type.getValue() + ".");
                }
                if (declaration.amountMin() != null || declaration.amountMax() != null) {
                    throw new InventoryException.InvalidRequest(
                            "A fixed fee carries an amount, not a range: " + type.getValue() + ".");
                }
            } else {
                if (declaration.amountMin() == null || declaration.amountMax() == null) {
                    throw new InventoryException.InvalidRequest(
                            "A variable fee needs both amountMin and amountMax: " + type.getValue() + ".");
                }
                if (declaration.amount() != null) {
                    throw new InventoryException.InvalidRequest(
                            "A variable fee carries a range, not an amount: " + type.getValue() + ".");
                }
                if (declaration.amountMax().compareTo(declaration.amountMin()) < 0) {
                    throw new InventoryException.InvalidRequest(
                            "A fee's range runs backwards: " + type.getValue() + ".");
                }
                // The whole reason ranges are permitted. Both source letters
                // justify a variable infrastructure fee by material prices,
                // which is a real constraint — but a fee that varies for no
                // stated reason is just a number that can change later.
                if (declaration.variationBasis() == null || declaration.variationBasis().isBlank()) {
                    throw new InventoryException.InvalidRequest(
                            "A variable fee must state why it varies (variationBasis): "
                                    + type.getValue() + ".");
                }
            }

            rows.add(EstateFee.builder()
                    .tenantId(estate.getTenantId())
                    .branchId(estate.getBranchId())
                    .estateId(estate.getId())
                    .feeType(type)
                    .label(label)
                    .amount(declaration.amount())
                    .amountMin(declaration.amountMin())
                    .amountMax(declaration.amountMax())
                    .currency(currency)
                    .isFixed(fixed)
                    .variationBasis(fixed ? null : declaration.variationBasis().trim())
                    .dueTrigger(trigger)
                    .refundable(Boolean.TRUE.equals(declaration.refundable()))
                    .isMandatory(Boolean.TRUE.equals(declaration.isMandatory()))
                    .notes(declaration.notes())
                    .build());
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Refund terms
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<RefundTermsDto> refundTerms(UUID estateId) {
        requireEstate(estateId);
        return refundTermsRepository.findFirstByEstateIdOrderByVersionDesc(estateId).map(this::toDto);
    }

    @Transactional
    public RefundTermsDto declareRefundTerms(UUID estateId, DeclareRefundTermsRequest request) {
        Estate estate = requireEstate(estateId);
        EstateWriteAccess.requireWritable(estate);
        RefundAppliesTo appliesTo = RefundAppliesTo.fromValue(request.appliesTo());

        String[] nonRefundable = (request.nonRefundableFeeTypes() == null
                ? List.<String>of() : request.nonRefundableFeeTypes()).stream()
                // Parsed rather than stored raw, so a typo becomes a 400 now
                // instead of a fee type that silently matches nothing later.
                .map(FeeType::fromValue)
                .map(FeeType::name)
                .distinct()
                .toArray(String[]::new);

        int version = refundTermsRepository.findFirstByEstateIdOrderByVersionDesc(estateId)
                .map(existing -> existing.getVersion() + 1).orElse(1);

        EstateRefundTerms terms = refundTermsRepository.save(EstateRefundTerms.builder()
                .tenantId(estate.getTenantId())
                .branchId(estate.getBranchId())
                .estateId(estateId)
                .version(version)
                .deductionPct(request.deductionPct())
                .processingDays(request.processingDays())
                .appliesTo(appliesTo)
                .nonRefundableFeeTypes(nonRefundable)
                .notes(request.notes())
                .build());

        log.info("Refund terms v{} declared for estate {} by actor {}", version, estateId, actorId());
        auditApi.record(AuditEntryRequest.of(
                actorId(), "estate.refund_terms_declared", "estate", estateId, estate.getTenantId(),
                "Refund terms version " + version + " declared"));

        return toDto(terms);
    }

    // ------------------------------------------------------------------
    // Default terms
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<DefaultTermsDto> defaultTerms(UUID estateId) {
        requireEstate(estateId);
        return defaultTermsRepository.findFirstByEstateIdOrderByVersionDesc(estateId)
                .map(terms -> toDto(terms,
                        penaltyTierRepository.findByDefaultTermsIdOrderByMonthsLateAsc(terms.getId())));
    }

    @Transactional
    public DefaultTermsDto declareDefaultTerms(UUID estateId, DeclareDefaultTermsRequest request) {
        Estate estate = requireEstate(estateId);
        EstateWriteAccess.requireWritable(estate);

        boolean hasPenalties = request.penaltyTiers() != null && !request.penaltyTiers().isEmpty();
        // Phase 4: whether penalties are refunded is the developer's to declare,
        // never assumed, whenever there are penalties to refund.
        if (hasPenalties && request.penaltiesRefundable() == null) {
            throw new InventoryException.InvalidRequest(
                    "Say whether late-payment penalties are refunded to a buyer who withdraws (penaltiesRefundable).");
        }
        int version = defaultTermsRepository.findFirstByEstateIdOrderByVersionDesc(estateId)
                .map(existing -> existing.getVersion() + 1).orElse(1);

        EstateDefaultTerms terms = defaultTermsRepository.save(EstateDefaultTerms.builder()
                .tenantId(estate.getTenantId())
                .branchId(estate.getBranchId())
                .estateId(estateId)
                .version(version)
                .revocationAfterMonths(request.revocationAfterMonths())
                .revocationNoticeDays(request.revocationNoticeDays())
                .developmentDeadlineMonths(request.developmentDeadlineMonths())
                .transferRequiresConsent(Boolean.TRUE.equals(request.transferRequiresConsent()))
                // No penalties: nothing to refund, so the question doesn't arise.
                .penaltiesRefundable(!hasPenalties || request.penaltiesRefundable())
                .notes(request.notes())
                .build());

        List<EstateDefaultPenaltyTier> tiers = new ArrayList<>();
        Set<Integer> months = new HashSet<>();
        for (DeclareDefaultTermsRequest.PenaltyTier tier
                : request.penaltyTiers() == null ? List.<DeclareDefaultTermsRequest.PenaltyTier>of()
                : request.penaltyTiers()) {
            if (!months.add(tier.monthsLate())) {
                throw new InventoryException.InvalidRequest(
                        "Two penalties declared for the same month: " + tier.monthsLate() + ".");
            }
            tiers.add(EstateDefaultPenaltyTier.builder()
                    .tenantId(estate.getTenantId())
                    .branchId(estate.getBranchId())
                    .defaultTermsId(terms.getId())
                    .monthsLate(tier.monthsLate())
                    .penaltyPct(tier.penaltyPct())
                    .build());
        }
        penaltyTierRepository.saveAll(tiers);
        tiers.sort((a, b) -> Integer.compare(a.getMonthsLate(), b.getMonthsLate()));

        log.info("Default terms v{} declared for estate {} ({} penalty tier(s)) by actor {}",
                version, estateId, tiers.size(), actorId());
        auditApi.record(AuditEntryRequest.of(
                actorId(), "estate.default_terms_declared", "estate", estateId, estate.getTenantId(),
                "Default terms version " + version + " declared with " + tiers.size() + " penalty tier(s)"));

        return toDto(terms, tiers);
    }

    // ------------------------------------------------------------------
    // Instalment plans (PL-1)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentPlansDto paymentPlans(UUID estateId) {
        Estate estate = requireEstate(estateId);
        return toDto(estate, paymentPlanRepository.findByEstateIdAndVersionOrderBySortOrderAsc(
                estateId, estate.getPaymentPlansVersion()));
    }

    /**
     * Replaces the estate's plans with a new version, as fees are. Terms only:
     * every figure is worked out from them. An empty list means outright only.
     * Buyers already on a plan keep the version they chose.
     */
    @Transactional
    public PaymentPlansDto declarePaymentPlans(UUID estateId, DeclarePaymentPlansRequest request) {
        Estate estate = requireEstate(estateId);
        EstateWriteAccess.requireWritable(estate);
        int version = estate.getPaymentPlansVersion() + 1;
        List<EstatePaymentPlan> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int order = 0;
        for (DeclarePaymentPlansRequest.Plan plan : request.plans()) {
            String name = plan.name().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new InventoryException.InvalidRequest("Two plans are called '" + name + "'.");
            }
            if ((plan.depositPct() == null) == (plan.depositAmount() == null)) {
                throw new InventoryException.InvalidRequest(
                        "Plan '" + name + "' needs a deposit as either a percentage or a fixed amount, not both.");
            }
            PaymentFrequency frequency;
            try {
                frequency = PaymentFrequency.fromValue(plan.frequency());
            } catch (IllegalArgumentException e) {
                throw new InventoryException.InvalidRequest(
                        "Plan '" + name + "': frequency must be 'monthly' or 'quarterly'.");
            }
            rows.add(EstatePaymentPlan.builder()
                    .tenantId(estate.getTenantId())
                    .branchId(estate.getBranchId())
                    .estateId(estateId)
                    .version(version)
                    .sortOrder(order++)
                    .name(name)
                    .depositPct(plan.depositPct())
                    .depositAmount(plan.depositAmount())
                    .instalmentCount(plan.instalmentCount())
                    .frequency(frequency)
                    .premiumPct(plan.premiumPct() == null ? BigDecimal.ZERO : plan.premiumPct())
                    .build());
        }
        paymentPlanRepository.saveAll(rows);
        estate.setPaymentPlansVersion(version);
        estateRepository.save(estate);

        log.info("Payment plans v{} declared for estate {} ({} plan(s)) by actor {}",
                version, estateId, rows.size(), actorId());
        auditApi.record(AuditEntryRequest.of(
                actorId(), "estate.payment_plans_declared", "estate", estateId, estate.getTenantId(),
                "Payment plans version " + version + " declared: " + (rows.isEmpty() ? "outright only"
                        : String.join("; ", rows.stream().map(EstateDisclosureService::describe).toList()))));
        return toDto(estate, rows);
    }

    private PaymentPlansDto toDto(Estate estate, List<EstatePaymentPlan> plans) {
        List<PriceTier> tiers = priceTierRepository.findByEstateIdOrderBySizeSqmAsc(estate.getId()).stream()
                .filter(t -> t.getRetiredAt() == null)
                .toList();
        return new PaymentPlansDto(estate.getPaymentPlansVersion(), plans.stream()
                .map(plan -> new PaymentPlansDto.Plan(plan.getId(), plan.getName(), plan.getDepositPct(),
                        plan.getDepositAmount(), plan.getInstalmentCount(), plan.getFrequency().getValue(),
                        plan.getPremiumPct(), tiers.stream()
                        .flatMap(tier -> InstalmentPlanCalculator.quote(tier.getPrice(), terms(plan))
                                .map(q -> new PaymentPlansDto.Quote(tier.getId(), tier.getLabel(),
                                        tier.getCurrency().name(), q.price(), q.planTotal(), q.premium(),
                                        q.deposit(), q.instalmentCount(), q.instalment(), q.lastInstalment()))
                                .stream())
                        .toList()))
                .toList());
    }

    static InstalmentPlanCalculator.Terms terms(EstatePaymentPlan plan) {
        return new InstalmentPlanCalculator.Terms(plan.getDepositPct(), plan.getDepositAmount(),
                plan.getInstalmentCount(), plan.getFrequency(), plan.getPremiumPct());
    }

    private static String describe(EstatePaymentPlan plan) {
        return plan.getName() + " (" + (plan.getDepositPct() != null
                ? plan.getDepositPct().stripTrailingZeros().toPlainString() + "% deposit"
                : plan.getDepositAmount().stripTrailingZeros().toPlainString() + " deposit")
                + ", " + plan.getInstalmentCount() + " " + plan.getFrequency().getValue() + ", premium "
                + plan.getPremiumPct().stripTrailingZeros().toPlainString() + "%)";
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Row-level security is what makes this an ownership check: another
     * tenant's estate simply is not there, so this reads as not-found rather
     * than forbidden — and there is no second, weaker tenant predicate in
     * application code to disagree with the policy.
     */
    private Estate requireEstate(UUID estateId) {
        return estateRepository.findById(estateId)
                .orElseThrow(InventoryException.EstateNotFound::new);
    }

    private static UUID actorId() {
        return TenantContext.get().map(TenantScope::userId).orElse(null);
    }

    private FeeDto toDto(EstateFee fee) {
        return new FeeDto(
                fee.getFeeType().getValue(),
                fee.getLabel(),
                fee.getAmount(),
                fee.getAmountMin(),
                fee.getAmountMax(),
                fee.getCurrency().name(),
                Boolean.TRUE.equals(fee.getIsFixed()),
                fee.getVariationBasis(),
                fee.getDueTrigger().getValue(),
                Boolean.TRUE.equals(fee.getRefundable()),
                Boolean.TRUE.equals(fee.getIsMandatory()),
                fee.getNotes());
    }

    private RefundTermsDto toDto(EstateRefundTerms terms) {
        return new RefundTermsDto(
                terms.getVersion(),
                terms.getDeductionPct(),
                terms.getProcessingDays(),
                terms.getAppliesTo().getValue(),
                terms.getNonRefundableFeeTypes() == null ? List.of()
                        : Arrays.stream(terms.getNonRefundableFeeTypes())
                        .map(FeeType::valueOf).map(FeeType::getValue).toList(),
                terms.getNotes());
    }

    private DefaultTermsDto toDto(EstateDefaultTerms terms, List<EstateDefaultPenaltyTier> tiers) {
        List<PenaltyTierDto> tierDtos = tiers.stream()
                .map(tier -> new PenaltyTierDto(tier.getMonthsLate(), tier.getPenaltyPct()))
                .toList();
        return new DefaultTermsDto(
                terms.getVersion(),
                terms.getRevocationAfterMonths(),
                terms.getRevocationNoticeDays(),
                RevocationTerms.offered(terms.getRevocationAfterMonths(), terms.getRevocationNoticeDays()).noticeDays(),
                terms.getDevelopmentDeadlineMonths(),
                Boolean.TRUE.equals(terms.getTransferRequiresConsent()),
                tierDtos,
                Boolean.TRUE.equals(terms.getPenaltiesRefundable()),
                terms.getNotes());
    }
}
